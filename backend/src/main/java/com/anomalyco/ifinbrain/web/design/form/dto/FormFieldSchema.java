package com.anomalyco.ifinbrain.web.design.form.dto;

import com.anomalyco.ifinbrain.web.design.form.enums.FieldType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

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

    // ===================== 条件逻辑（表达式） =====================
    //
    // 下面三个都是单表达式，取值语义为布尔：非空且求值为 true 才生效。
    // 求值失败（语法错 / 引用了不存在的字段）一律按 false 处理 —— 宁可少显示，
    // 也不要因为一个坏表达式把整张表单卡死。

    /**
     * 显示条件，如 {@code {{ $form.type === 'company' }}}。
     * 为空 = 恒显示。false 时字段不渲染，且不参与校验。
     */
    private String visibleWhen;

    /**
     * 禁用条件，如 {@code {{ $form.locked }}}。为空 = 恒可编辑。
     *
     * <p>注意与 visibleWhen 正交：禁用只挡交互，值仍会照常提交。
     */
    private String disabledWhen;

    /**
     * 必填条件，如 {@code {{ $form.needInvoice }}}。为空 = 沿用字段级 required。
     *
     * <p>与字段级 required 是「或」：两者任一为真即必填。
     */
    private String requiredWhen;

    /**
     * 动态选项源（select / radio / checkbox）。
     *
     * <p><b>必须在本 DTO 上声明</b>：Jackson 默认静默丢弃未知字段，
     * 前端多传一个 optionsSource 而后端不加，保存时配置会被吃掉且接口照样返回 200。
     *
     * <p>与静态 {@link #options} 二选一：配了本项就以查询结果为准。
     */
    private FormFieldOptionsSource optionsSource;

    // ===================== 表格动态列源 =====================

    /**
     * 表格的动态列源（仅 {@link FieldType#TABLE}）：列由查询结果集自动匹配。
     *
     * <p><b>必须在本 DTO 上声明</b>：Jackson 默认静默丢弃未知字段。
     * 与 {@link #optionsSource} 是同一套思路的表格版，区别是它匹配的是「列」
     * 并且带一个动态更新开关（{@link FormFieldTableSource#autoSync}）。
     */
    private FormFieldTableSource tableSource;

    // ===================== 栅格布局 / 样式 =====================

    /**
     * 24 列栅格中的位置。
     *
     * <p>可选：老 schema 没有这个字段，加载时由前端按老的 {@code fullWidth}
     * 语义自动补齐，补齐后渲染只剩一条路径。
     *
     * <p><b>必须在本 DTO 上声明</b>：Jackson 默认静默丢弃未知字段。
     */
    private FormFieldLayout layout;

    /**
     * 字段级样式覆盖。没配的属性走主题默认。
     *
     * <p><b>必须在本 DTO 上声明</b>：Jackson 默认静默丢弃未知字段。
     */
    private Map<String, Object> style;
}