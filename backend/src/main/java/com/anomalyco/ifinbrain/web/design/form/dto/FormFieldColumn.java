package com.anomalyco.ifinbrain.web.design.form.dto;

import com.anomalyco.ifinbrain.web.design.form.enums.FieldType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 表格（{@link FieldType#TABLE}）的一列定义。
 *
 * <p>运行时值形状：表格字段的值是对象数组，列 key 即行对象的属性名。
 * <pre>
 * "expenseItems": [ { "name": "机票", "amount": 1200 }, { "name": "住宿", "amount": 800 } ]
 * </pre>
 *
 * <p>列 type 排除 {@link FieldType#DIVIDER}（布局块，放进单元格无意义）。
 * 值为 null 时按 TEXT 处理。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FormFieldColumn {

    /** 列标识 —— 行对象里的属性名；同一张表内唯一 */
    private String key;

    /** 列标题（表头文案） */
    private String label;

    /** 单元格控件类型；null 按 TEXT 处理 */
    private FieldType type;

    /** 列宽（像素）；null 表示自适应 */
    private Integer width;

    /** 该列单元格是否必填 —— 仅在表格开启 requireAllCells 时才参与校验 */
    private Boolean required;

    /** 选项列表（列 type 为 select/radio/checkbox 时必填） */
    private List<FormFieldOption> options;
}
