package com.anomalyco.fapiao.web.common;

import com.anomalyco.fapiao.web.entity.InvoiceRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 发票记录导出为 CSV / XLSX 的工具。XLSX 用 Apache POI SXSSFWorkbook 输出真正的 OOXML .xlsx。 */
public final class InvoiceExport {

    /** 与列表页列对齐的导出字段顺序。 */
    public static final String[] HEADERS = {
        "ID", "文件", "票种", "发票号", "开票日期",
        "销售方", "销售方税号", "购买方", "购买方税号",
        "不含税金额", "税额", "价税合计", "价税合计(大写)",
        "收款人", "复核人", "开票人", "状态",
        "核销单号", "核销人", "核销日期", "创建时间"
    };

    private InvoiceExport() {}

    /** 转义 CSV 字段：含 , " 换行 时整体加双引号，内部 " 重复一次。 */
    public static String csvEscape(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    public static String formatDate(LocalDate d) {
        return d == null ? "" : d.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
    public static String formatDateTime(LocalDateTime d) {
        return d == null ? "" : d.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
    public static String formatNum(BigDecimal n) {
        return n == null ? "" : n.toPlainString();
    }

    /** 写 CSV 文件头（BOM + 列名）。每次 export 一个流都先调一次。 */
    public static void writeCsvHeader(OutputStream out) throws IOException {
        // UTF-8 BOM 让 Excel 正确识别中文
        out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < HEADERS.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(csvEscape(HEADERS[i]));
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 追加一行 CSV。每行直接 flush 到底层 OutputStream，无中间缓冲。 */
    public static void appendCsvRow(OutputStream out, InvoiceRecord r) throws IOException {
        StringBuilder sb = new StringBuilder();
        Object[] cells = rowToCells(r);
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(csvEscape(cells[i]));
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 创建 SXSSFWorkbook + Invoices sheet + 表头 + 列宽样式。
     *  调用方持有 workbook，用 appendXlsxRow 追加，最后 workbook.write(out) 写完关闭。 */
    public static SXSSFWorkbook initXlsx() {
        SXSSFWorkbook wb = new SXSSFWorkbook(100);
        wb.setCompressTempFiles(true);

        CellStyle headerStyle = wb.createCellStyle();
        Font hf = wb.createFont();
        hf.setBold(true);
        headerStyle.setFont(hf);
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);

        Sheet sheet = wb.createSheet("Invoices");
        int[] widths = {8, 32, 28, 26, 14, 22, 22, 22, 22, 14, 12, 14, 24, 12, 12, 12, 14, 18, 14, 14, 22};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        Row hdr = sheet.createRow(0);
        for (int i = 0; i < HEADERS.length; i++) {
            Cell c = hdr.createCell(i);
            c.setCellValue(HEADERS[i]);
            c.setCellStyle(headerStyle);
        }
        return wb;
    }

    /** 追加一行 XLSX。rIdx 从 1 开始自增。 */
    public static void appendXlsxRow(Sheet sheet, int rIdx, InvoiceRecord rec) {
        Object[] cells = rowToCells(rec);
        Row row = sheet.createRow(rIdx);
        for (int i = 0; i < cells.length; i++) {
            Cell c = row.createCell(i);
            Object v = cells[i];
            if (v == null) continue;
            if (v instanceof Number n) {
                c.setCellValue(n.doubleValue());
            } else {
                c.setCellValue(String.valueOf(v));
            }
        }
    }

    /**
     * 一次性导出（保留供测试 / 小数据用）。
     * 生产路径已改用 {@link #writeCsvHeader} + {@link #appendCsvRow} 流式输出。
     */
    public static void writeCsv(List<InvoiceRecord> rows, OutputStream out) throws IOException {
        writeCsvHeader(out);
        for (InvoiceRecord r : rows) {
            appendCsvRow(out, r);
        }
        out.flush();
    }

    /** 一次性 XLSX（保留供测试 / 小数据用）。 */
    public static void writeXlsx(List<InvoiceRecord> rows, OutputStream out) throws IOException {
        try (SXSSFWorkbook wb = initXlsx()) {
            Sheet sheet = wb.getSheet("Invoices");
            int rIdx = 1;
            for (InvoiceRecord rec : rows) {
                appendXlsxRow(sheet, rIdx++, rec);
            }
            wb.write(out);
            out.flush();
        }
    }

    private static Object[] rowToCells(InvoiceRecord r) {
        return new Object[] {
            r.getId(),
            r.getOriginalFilename(),
            r.getInvoiceType(),
            r.getInvoiceNumber(),
            formatDate(r.getIssueDate()),
            r.getSellerName(),
            r.getSellerTaxId(),
            r.getBuyerName(),
            r.getBuyerTaxId(),
            formatNum(r.getAmountWithoutTax()),
            formatNum(r.getTaxAmount()),
            formatNum(r.getTotalAmount()),
            r.getTotalAmountUpper(),
            r.getPayee(),
            r.getReviewer(),
            r.getDrawer(),
            r.getMessage(),
            r.getVerificationNo(),
            r.getVerifier(),
            formatDate(r.getVerificationDate()),
            formatDateTime(r.getCreatedAt())
        };
    }
}