package com.anomalyco.ifinbrain.web.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 把存储里的文件字节流抽成纯文本，供分块 → embedding。
 *
 * <p>支持范围按 dev 库实测的档案构成定的（14 个样本：pdf×4、xlsx、docx、md、xml、
 * json、yml、conf、svg、jpg、ofd）：PDF / Word / Excel / 纯文本。
 *
 * <p>明确不支持：图片（jpg 等）与 OFD 版式文件 —— 二者都需要 OCR 或专用解析器，
 * 这里返回 {@code supported=false} 并说明原因，<b>不静默产出空文本</b>：空文本入库
 * 等于生成一批永远检索不到、还占着 embedding 配额的垃圾分块。
 */
@Slf4j
@Component
public class TextExtractor {

    /** 按扩展名/后缀判定的纯文本类型。 */
    private static final Set<String> TEXT_EXT = Set.of(
            "txt", "md", "markdown", "json", "yml", "yaml", "xml", "conf", "ini",
            "properties", "csv", "log", "sql", "js", "ts", "java", "py", "sh", "html", "htm");

    /** 超过这个大小就不抽了，避免一份几百 MB 的表格把堆打爆。 */
    private static final int MAX_BYTES = 64 * 1024 * 1024;

    /**
     * @param text       抽出的正文；{@code supported=false} 时为 null
     * @param supported  该类型是否被支持
     * @param note       面向人的说明（前端展示 / 日志）
     */
    public record ExtractResult(String text, boolean supported, String note) {
        static ExtractResult ok(String t) {
            return new ExtractResult(t, true, null);
        }

        static ExtractResult skip(String note) {
            return new ExtractResult(null, false, note);
        }

        public boolean isEmpty() {
            return text == null || text.isBlank();
        }
    }

    public ExtractResult extract(String filename, String mimeType, byte[] content) {
        if (content == null || content.length == 0) {
            return ExtractResult.skip("文件内容为空");
        }
        if (content.length > MAX_BYTES) {
            return ExtractResult.skip("文件超过 " + (MAX_BYTES / 1024 / 1024) + "MB，未做文本抽取");
        }
        String ext = extOf(filename);

        try {
            return switch (ext) {
                case "pdf" -> fromPdf(content);
                case "docx" -> fromDocx(content);
                case "xlsx", "xlsm", "xls" -> fromExcel(content);
                default -> {
                    if (TEXT_EXT.contains(ext)) {
                        yield ExtractResult.ok(decodeUtf8(content));
                    }
                    if (mimeType != null && mimeType.startsWith("text/")) {
                        yield ExtractResult.ok(decodeUtf8(content));
                    }
                    yield ExtractResult.skip("暂不支持的文件类型：" + ext
                            + "（图片需 OCR，OFD 需专用解析器）");
                }
            };
        } catch (Exception ex) {
            // 解析失败不能吞掉：否则调用方会以为"抽到了但为空"，进而写入空分块
            log.warn("[rag] extract failed for {} ({}): {}", filename, ext, ex.toString());
            return ExtractResult.skip("解析失败：" + ex.getClass().getSimpleName());
        }
    }

    private ExtractResult fromPdf(byte[] content) throws Exception {
        try (PDDocument doc = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            if (text == null || text.isBlank()) {
                // 扫描件 / 图片型 PDF：没有文本层，PDFBox 抽出来必然是空。
                // 这是最容易被静默吞掉的一种，必须让调用方知道"要上 OCR"。
                return ExtractResult.skip("PDF 无文本层（可能是扫描件），需 OCR 后才能入库");
            }
            return ExtractResult.ok(text);
        }
    }

    private ExtractResult fromDocx(byte[] content) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content));
             XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
            String text = ex.getText();
            if (text == null || text.isBlank()) {
                return ExtractResult.skip("Word 文档无文字内容（可能是纯图片排版）");
            }
            return ExtractResult.ok(text);
        }
    }

    private ExtractResult fromExcel(byte[] content) throws Exception {
        StringBuilder sb = new StringBuilder();
        DataFormatter fmt = new DataFormatter(Locale.ROOT);
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                sb.append("\n## 工作表：").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    for (Cell cell : row) {
                        String v = fmt.formatCellValue(cell);
                        if (v != null && !v.isBlank()) {
                            if (line.length() > 0) line.append('\t');
                            line.append(v);
                        }
                    }
                    if (line.length() > 0) {
                        sb.append(line).append('\n');
                    }
                }
            }
        }
        if (sb.isEmpty()) {
            return ExtractResult.skip("Excel 无单元格内容");
        }
        return ExtractResult.ok(sb.toString());
    }

    /**
     * 宽容解码：档案里的 xml/json 多是 UTF-8，但 GBK 也不算罕见。
     * 严格 UTF-8 失败时退回 GBK，都不成才做替换字符兜底，绝不抛异常丢整份文档。
     */
    private static String decodeUtf8(byte[] b) {
        CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return strict.decode(java.nio.ByteBuffer.wrap(b)).toString();
        } catch (Exception utf8Fail) {
            try {
                // GBK 不在 StandardCharsets 常量集里，只能按名取
                return java.nio.charset.Charset.forName("GBK").newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)
                        .decode(java.nio.ByteBuffer.wrap(b)).toString();
            } catch (Exception gbkFail) {
                return new String(b, StandardCharsets.UTF_8);
            }
        }
    }

    private static String extOf(String filename) {
        if (filename == null) return "";
        int i = filename.lastIndexOf('.');
        return i < 0 ? "" : filename.substring(i + 1).toLowerCase(Locale.ROOT);
    }
}
