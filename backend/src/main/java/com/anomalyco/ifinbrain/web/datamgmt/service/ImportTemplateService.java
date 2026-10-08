package com.anomalyco.ifinbrain.web.datamgmt.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.datamgmt.dto.DatasourceSchemaDto;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 按目标表的真实列结构生成导入模板 xlsx。
 *
 * <p>这是投入产出比最高的一个小功能：用户不用凭想象猜「这个字段该填日期还是文本」，
 * 也不容易因为列名对不上而在映射界面里手工拖 20 次。
 *
 * <p>刻意做成<b>两个 sheet</b>：
 * <ul>
 *   <li>{@code 数据}：第 1 行是列名，第 2 行开始就是数据区。类型提示绝不能混在这里 ——
 *       用户下载后没删掉提示行直接填数据，那一行会被当成第一条数据导入。</li>
 *   <li>{@code 填写说明}：每列的类型、长度、是否可空、自增列。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportTemplateService {

    private final ImportTargetResolver resolver;
    private final PermissionChecker perm;

    /** 生成模板字节流 */
    public byte[] build(Long datasourceId, String schema, String table) {
        perm.require("data:import:view");
        ImportTargetResolver.Target target =
                resolver.resolve(datasourceId, schema, table, "data:import:view");

        List<DatasourceSchemaDto.ColumnSchema> cols = target.writableColumns();
        if (cols.isEmpty()) {
            throw new BizException(400, "该表没有可导入的列（全部是自增/生成列）");
        }

        try (SXSSFWorkbook wb = new SXSSFWorkbook(50);
             var out = new java.io.ByteArrayOutputStream()) {

            Sheet data = wb.createSheet("数据");
            CellStyle headerStyle = headerStyle(wb);
            Row head = data.createRow(0);
            for (int i = 0; i < cols.size(); i++) {
                var c = head.createCell(i);
                c.setCellValue(cols.get(i).getName());
                c.setCellStyle(headerStyle);
            }

            Sheet doc = wb.createSheet("填写说明");
            CellStyle docHeader = headerStyle(wb);
            String[] titles = {"列名", "数据库类型", "业务类型", "可空", "长度/精度", "示例值"};
            Row docHead = doc.createRow(0);
            for (int i = 0; i < titles.length; i++) {
                var c = docHead.createCell(i);
                c.setCellValue(titles[i]);
                c.setCellStyle(docHeader);
            }
            for (int i = 0; i < cols.size(); i++) {
                var col = cols.get(i);
                Row r = doc.createRow(i + 1);
                r.createCell(0).setCellValue(col.getName());
                r.createCell(1).setCellValue(col.getDataType() == null ? "" : col.getDataType());
                r.createCell(2).setCellValue(typeCn(col.getType()));
                r.createCell(3).setCellValue(Boolean.FALSE.equals(col.getNullable()) ? "必填" : "可空");
                r.createCell(4).setCellValue(sizeCn(col));
                r.createCell(5).setCellValue(sample(col.getType()));
            }
            doc.setColumnWidth(0, 28 * 256);
            doc.setColumnWidth(1, 20 * 256);

            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("[import] template build failed {}.{} : {}", schema, table, ex.toString());
            throw new BizException(400, "生成导入模板失败: " + ex.getMessage());
        }
    }

    private CellStyle headerStyle(SXSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.LEFT);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        return s;
    }

    private String typeCn(String type) {
        if (type == null) return "未知";
        return switch (type) {
            case "number"  -> "数字";
            case "date"    -> "日期";
            case "datetime"-> "日期时间";
            case "boolean" -> "是/否";
            default        -> "文本";
        };
    }

    private String sizeCn(DatasourceSchemaDto.ColumnSchema c) {
        if (c.getMaxLength() != null) return "最多 " + c.getMaxLength() + " 字符";
        if (c.getPrecision() != null) {
            return c.getScale() != null
                    ? "共 " + c.getPrecision() + " 位，小数 " + c.getScale() + " 位"
                    : "共 " + c.getPrecision() + " 位";
        }
        return "不限";
    }

    private String sample(String type) {
        if (type == null) return "";
        return switch (type) {
            case "number"  -> "1234.56";
            case "date"    -> "2024-01-31";
            case "datetime"-> "2024-01-31 09:30:00";
            case "boolean" -> "是 / 否 / 1 / 0";
            default        -> "文本内容";
        };
    }
}