package com.anomalyco.ifinbrain.web.design.form.dto;

import com.anomalyco.ifinbrain.web.design.form.enums.FieldType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 表单字段 schema —— 描述一个表单输入控件的完整定义。
 * 存储在 sys_form_design.fields_json，运行时由前端 DynamicFormRenderer 解析渲染。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FormFieldSchema {

    /** 字段唯一 id（前端生成；保存后稳定，用于 diff / 排序） */
    private String id;

    /** 字段类型（见 FieldType） */
    private FieldType type;

    /** 字段 key（业务标识，必须 kebab-case 或 camelCase，全表单唯一） */
    private String key;

    /** 显示标签 */
    private String label;

    /** 是否必填 */
    private Boolean required;

    /** 默认值（字符串形式，按 type 自行转） */
    private String defaultValue;

    /** 占位提示 */
    private String placeholder;

    /** 帮助文字（可选） */
    private String help;

    /**
     * 选项列表（仅 select/radio/checkbox 必填）
     * 元素：{ label, value } —— value 是 string 形式；多选用 "," 分隔
     */
    private List<FormFieldOption> options;

    /**
     * 简单校验（仅 text/textarea/number 生效）
     * { min: Integer, max: Integer, pattern: String } —— null 表示无约束
     */
    private FormFieldValidator validator;

    /**
     * 是否占整行（默认 true；false 时与下一个字段共享一行 —— 多用于两个 short field 并排）
     * 前端按此控制布局。
     */
    private Boolean fullWidth;

    /**
     * 表格列定义（仅 {@link FieldType#TABLE}）。
     *
     * <p><b>必须在本 DTO 上声明</b>：Jackson 默认静默丢弃未知字段，前端多传一个
     * columns 而后端不加，保存时列配置会被吃掉且接口照样返回 200。
     */
    private List<FormFieldColumn> columns;

    /**
     * 表格「每行填满必填列」开关（仅 TABLE，与字段级 {@code required} 正交）。
     *
     * <ul>
     *   <li>{@code required=false, requireAllCells=false} → 不校验</li>
     *   <li>{@code required=true,  requireAllCells=false} → 至少填一行，行内不校验</li>
     *   <li>{@code required=false, requireAllCells=true}  → 可留空，但填了就必须填完整</li>
     *   <li>{@code required=true,  requireAllCells=true}  → 至少一行，且每行填满必填列</li>
     * </ul>
     */
    private Boolean requireAllCells;
}