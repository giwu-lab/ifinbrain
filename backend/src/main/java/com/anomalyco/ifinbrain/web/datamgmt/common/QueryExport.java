package com.anomalyco.ifinbrain.web.datamgmt.common;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.Writer;

/**
 * 自定义 SQL 查询结果导出 CSV / XLSX 的工具（与发票导出同套手法）：
 *   - CSV：RFC 4180 escape + UTF-8 BOM
 *   - XLSX：Apache POI SXSSFWorkbook（行级流式，内存只保留最近 100 行）
 *
 * 列在运行时由 ResultSetMetaData 推断，不在编译期固定。
 */
public final class QueryExport {

    private QueryExport() {}

    /** RFC 4180 escape：含 , " 换行 时整体加双引号；" 重复一次。 */
    public static String csvCell(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** 写一行 CSV（CRLF 换行，兼容 Excel / Windows 工具） */
    public static void writeCsvRow(Writer w, Object[] cells) throws IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) w.write(',');
            w.write(csvCell(cells[i]));
        }
        w.write("\r\n");
    }

    /** XLSX header 样式：灰底 + 加粗 */
    public static CellStyle createHeaderStyle(SXSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.LEFT);
        return s;
    }

    /** 把一行写到 XLSX Row；数字按 double 写入、布尔按 boolean、其它按 string。 */
    public static void writeXlsxRow(Sheet sheet, int rowIdx, Object[] values) {
        Row r = sheet.createRow(rowIdx);
        int n = values.length;
        for (int i = 0; i < n; i++) {
            Cell c = r.createCell(i);
            Object v = values[i];
            if (v == null) {
                /* 留空 */
            } else if (v instanceof Number num) {
                c.setCellValue(num.doubleValue());
            } else if (v instanceof Boolean b) {
                c.setCellValue(b);
            } else {
                c.setCellValue(String.valueOf(v));
            }
        }
    }
}
