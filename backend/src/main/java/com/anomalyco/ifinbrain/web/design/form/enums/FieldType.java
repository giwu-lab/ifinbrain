package com.anomalyco.ifinbrain.web.design.form.enums;

/**
 * 表单字段类型枚举。
 *
 * <p>与前端 {@code components/form/fields/*} 一一对应；后端白名单同步，防止前端传入未知 type。
 *
 * <p>14 种类型按 UI 控件分类：
 * <ul>
 *   <li>基础输入：text / textarea / number</li>
 *   <li>选择类：select / radio / checkbox</li>
 *   <li>时间类：date / datetime</li>
 *   <li>业务选取：user / dept</li>
 *   <li>特殊：file / rich_text / table</li>
 *   <li>布局：divider</li>
 * </ul>
 */
public enum FieldType {
    TEXT,
    TEXTAREA,
    NUMBER,
    SELECT,
    RADIO,
    CHECKBOX,
    DATE,
    DATETIME,
    USER,
    DEPT,
    FILE,
    RICH_TEXT,
    TABLE,
    DIVIDER;

    /** 是否需要 options 数组（select / radio / checkbox） */
    public boolean needsOptions() {
        return this == SELECT || this == RADIO || this == CHECKBOX;
    }

    /** 是否需要 validator（min/max/pattern） */
    public boolean needsValidator() {
        return this == TEXT || this == TEXTAREA || this == NUMBER;
    }

    /**
     * 能否作为 TABLE 字段的列类型。
     *
     * <p>排除两种：
     * <ul>
     *   <li>{@link #DIVIDER} —— 整块布局元素（画一条横线），放进单元格没有意义</li>
     *   <li>{@link #TABLE} —— 不支持表格嵌套表格。前端 {@code cellRegistry.ts} 同样只注册
     *       12 种单元格控件，不含 TABLE；若这里放行，嵌套表格会被
     *       {@code resolveCell()} 静默降级成文本框，看起来"能用"但存不进结构化数据</li>
     * </ul>
     */
    public boolean usableAsColumn() {
        return this != DIVIDER && this != TABLE;
    }
}