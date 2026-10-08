package com.anomalyco.ifinbrain.web.datamgmt.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 上传文件的解析：xlsx / xls（POI）+ csv（手写 RFC 4180 解析器）。
 *
 * <p><b>为什么在后端解析</b>（前端也装了 SheetJS，但只用于导出）：
 * <ol>
 *   <li>类型转换必须以<strong>目标列的真实元数据</strong>为准，而目标元数据只有后端能拿到；</li>
 *   <li>写入本来就要在后端批量 INSERT，前端解析等于把整表转成 JSON 再传一遍 ——
 *       5 万行 × 30 列的 JSON 就是几十上百 MB，且 Excel 日期序列号在传输中会退化；</li>
 *   <li>导出链路已经用 POI（{@code QueryExport}），导入复用同一个库 = 同一套口径。</li>
 * </ol>
 *
 * <p><b>内存</b>：POI 读 xlsx 用 {@code XSSFWorkbook}，会把整个 sheet 载入堆内存
 * （约为文件体积的 5~10 倍）。所以行数闸门（{@code maxRows}）必须**在读取过程中**
 * 生效 —— 读到上限就 break，绝不是先读进来再判断。
 *
 * <p><b>公式单元格</b>：取 <strong>cached value</strong> 而不是公式文本。
 * 否则源文件里 {@code =1+1} 会作为字符串 {@code "=1+1"} 写进库，
 * 下游任何把它当公式/命令执行的系统都会中招（导入侧对应导出侧的 CSV formula injection 防护）。
 */
@Slf4j
@Component
public class ImportFileReader {

    private static final DateTimeFormatter DATE_OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 支持的扩展名 → 解析方式 */
    public enum Kind { XLSX, XLS, CSV }

    public record ParsedFile(
            Kind kind,
            List<String> sheetNames,
            String usedSheet,
            List<String> headers,
            List<List<String>> rows,
            int totalRows,
            boolean truncated,
            String detectedEncoding) {}

    /**
     * 解析上传文件。
     *
     * @param sheetName   Excel 指定 sheet；null / 空 → 第一个
     * @param hasHeader   首行是否为表头。false 时首行视为数据，表头自动命名为「列1/列2/…」
     * @param encoding    CSV 编码；{@code auto} → BOM 探测 + UTF-8 严格解码失败回落 GB18030
     * @param maxRows     数据行上限（含表头行之后的全部行），超出即停并置 truncated
     */
    public ParsedFile read(MultipartFile file,
                           String sheetName,
                           boolean hasHeader,
                           String encoding,
                           int maxRows) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择要导入的文件");
        }
        String fname = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        Kind kind = kindOf(fname);
        try {
            return switch (kind) {
                case XLSX, XLS -> readExcel(file, sheetName, hasHeader, maxRows);
                case CSV       -> readCsv(file, hasHeader, encoding, maxRows);
            };
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("[import] parse failed file={} : {}", fname, ex.toString());
            throw new BizException(400, "文件解析失败: " + rootMessage(ex));
        }
    }

    /** 按扩展名判定；未知扩展名一律拒绝（避免 POI 用错 reader 报一堆天书） */
    public Kind kindOf(String filename) {
        String n = filename == null ? "" : filename.toLowerCase();
        if (n.endsWith(".xlsx") || n.endsWith(".xlsm")) return Kind.XLSX;
        if (n.endsWith(".xls"))  return Kind.XLS;
        if (n.endsWith(".csv"))  return Kind.CSV;
        throw new BizException(400, "仅支持 .xlsx / .xls / .csv 文件");
    }

    // ==================== Excel ====================

    private ParsedFile readExcel(MultipartFile file, String sheetName, boolean hasHeader, int maxRows) throws Exception {
        List<String> headers;
        List<List<String>> rows = new ArrayList<>();
        List<String> sheetNames = new ArrayList<>();
        String used;
        boolean truncated = false;

        try (InputStream in = file.getInputStream();
             Workbook wb = WorkbookFactory.create(in)) {

            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                sheetNames.add(wb.getSheetName(i));
            }
            Sheet sheet = (sheetName == null || sheetName.isBlank())
                    ? wb.getSheetAt(0)
                    : wb.getSheet(sheetName);
            if (sheet == null) {
                throw new BizException(400, "工作表不存在: " + sheetName
                        + "（该文件包含: " + String.join(" / ", sheetNames) + "）");
            }
            used = sheet.getSheetName();

            List<List<String>> all = new ArrayList<>();
            for (Row row : sheet) {
                // 行数闸门在读取过程中生效：超了直接停，不把整表读进内存
                if (all.size() > maxRows) {
                    truncated = true;
                    break;
                }
                List<String> cells = new ArrayList<>();
                int last = row.getLastCellNum();
                if (last <= 0) continue;
                for (int c = 0; c < last; c++) {
                    cells.add(cellText(row.getCell(c)));
                }
                // 整行空白直接丢：Excel 尾部空行 / 中间分隔行是常态
                if (isBlankRow(cells)) continue;
                all.add(trimTrailingEmpty(cells));
            }
            rows.addAll(all);
        }

        return splitHeader(rows, hasHeader, Kind.XLSX, sheetNames, used, truncated, null);
    }

    /** POI 单元格 → 文本。日期已格式化成 ISO 串（比序列号更不容易被误读）。 */
    private String cellText(Cell cell) {
        if (cell == null) return "";
        CellType t = cell.getCellType();
        // 公式：取缓存值，绝不取公式文本
        if (t == CellType.FORMULA) {
            t = cell.getCachedFormulaResultType();
        }
        return switch (t) {
            case STRING  -> cell.getStringCellValue();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    var v = cell.getLocalDateTimeCellValue();
                    yield (v.getHour() == 0 && v.getMinute() == 0 && v.getSecond() == 0)
                            ? v.toLocalDate().format(DATE_OUT)
                            : v.format(DATETIME_OUT);
                }
                yield plainNumber(cell.getNumericCellValue());
            }
            case ERROR, BLANK, _NONE, FORMULA -> "";
        };
    }

    /** 数字 → 文本：走 BigDecimal.toPlainString()，避免 double 的科学计数法（如 1.0E-5）污染数据 */
    private String plainNumber(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    // ==================== CSV ====================

    private ParsedFile readCsv(MultipartFile file, boolean hasHeader, String encoding, int maxRows) throws Exception {
        byte[] bytes = file.getBytes();
        String enc = (encoding == null || encoding.isBlank() || "auto".equalsIgnoreCase(encoding))
                ? "auto" : encoding;
        String content = decode(bytes, enc);

        List<List<String>> all = parseCsv(content, maxRows + 1);
        boolean truncated = all.size() > maxRows;
        if (truncated) {
            all = new ArrayList<>(all.subList(0, maxRows));
        }
        // 同样丢掉整行空白
        all.removeIf(this::isBlankRow);
        return splitHeader(all, hasHeader, Kind.CSV, List.of(), null, truncated, enc);
    }

    /**
     * 解码：auto 模式下先看 BOM，再用 UTF-8 严格解码试一次，失败回落 GB18030。
     *
     * <p>GB18030 而不是 GBK：GBK 是 GB18030 的子集，用 GB18030 能顺带覆盖
     * Excel 导出的生僻字（GBK 解不出的字节在 GB18030 里多半有解），
     * 且它是 GBK 的超集，对纯 GBK 文件行为完全一致。
     */
    private String decode(byte[] bytes, String encoding) throws CharacterCodingException {
        if ("auto".equals(encoding)) {
            if (startsWithUtf8Bom(bytes)) {
                return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
            }
            try {
                return strictDecode(bytes, StandardCharsets.UTF_8);
            } catch (CharacterCodingException ex) {
                log.debug("[import] not valid UTF-8, falling back to GB18030");
                return new String(bytes, java.nio.charset.Charset.forName("GB18030"));
            }
        }
        try {
            return new String(bytes, Charset.forName(encoding));
        } catch (Exception ex) {
            throw new BizException(400, "不支持的编码: " + encoding);
        }
    }

    private String strictDecode(byte[] bytes, Charset cs) throws CharacterCodingException {
        CharsetDecoder dec = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return dec.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private boolean startsWithUtf8Bom(byte[] b) {
        return b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF;
    }

    /**
     * RFC 4180 解析器（手写；POI 不含 CSV reader，commons-csv 未引入）。
     * 处理：双引号包裹字段、双引号转义（""）、字段内逗号 / 换行、\r\n 与 \n 与裸 \r。
     */
    private List<List<String>> parseCsv(String content, int maxRows) {
        List<List<String>> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int n = content.length();

        while (i < n) {
            char c = content.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && content.charAt(i + 1) == '"') {   // "" → 一个 "
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                field.append(c);
                i++;
                continue;
            }

            switch (c) {
                case '"' -> { inQuotes = true; i++; }
                case ',' -> { cur.add(field.toString()); field.setLength(0); i++; }
                case '\r' -> i++;                                   // 统一忽略 CR
                case '\n' -> {
                    cur.add(field.toString());
                    field.setLength(0);
                    rows.add(cur);
                    cur = new ArrayList<>();
                    i++;
                    if (rows.size() > maxRows) return rows;          // 提前退出，别把整文件读进来
                }
                default -> { field.append(c); i++; }
            }
        }
        // 文件末尾没有换行时的最后一行
        if (field.length() > 0 || !cur.isEmpty()) {
            cur.add(field.toString());
            rows.add(cur);
        }
        return rows;
    }

    // ==================== 公共 ====================

    /** 拆表头：无表头时首行升为数据，表头自动命名「列1…列N」 */
    private ParsedFile splitHeader(List<List<String>> rows,
                                   boolean hasHeader,
                                   Kind kind,
                                   List<String> sheetNames,
                                   String usedSheet,
                                   boolean truncated,
                                   String encoding) {

        List<String> headers;
        List<List<String>> data;
        if (hasHeader && !rows.isEmpty()) {
            headers = new ArrayList<>(rows.get(0));
            data = new ArrayList<>(rows.subList(1, rows.size()));
        } else {
            data = rows;
            int width = 0;
            for (List<String> r : data) width = Math.max(width, r.size());
            headers = new ArrayList<>(width);
            for (int i = 0; i < width; i++) headers.add("列" + (i + 1));
        }
        // 表头空位补名，避免映射界面出现 unnamed 列
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i) == null || headers.get(i).isBlank()) {
                headers.set(i, "列" + (i + 1));
            }
        }
        return new ParsedFile(kind, sheetNames, usedSheet, headers, data, data.size(), truncated, encoding);
    }

    private boolean isBlankRow(List<String> cells) {
        for (String c : cells) {
            if (c != null && !c.isBlank()) return false;
        }
        return true;
    }

    /** 砍掉行尾的空单元格：Excel 里列宽导致的尾部空列会让每行都「比上一行长」 */
    private List<String> trimTrailingEmpty(List<String> cells) {
        int end = cells.size();
        while (end > 0 && (cells.get(end - 1) == null || cells.get(end - 1).isBlank())) end--;
        return new ArrayList<>(cells.subList(0, end));
    }

    private String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
}