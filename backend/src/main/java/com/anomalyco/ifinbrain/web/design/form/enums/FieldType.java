package com.anomalyco.ifinbrain.web.design.form.enums;

/**
 * 表单字段类型枚举。
 *
 * <p>与前端 {@code components/form/fields/*} 一一对应；后端白名单同步，防止前端传入未知 type。
 *
 * <p>13 种类型按 UI 控件分类：
 * <ul>
 *   <li>基础输入：text / textarea / number</li>
 *   <li>选择类：select / radio / checkbox</li>
 *   <li>时间类：date / datetime</li>
 *   <li>业务选取：user / dept</li>
 *   <li>特殊：file / rich_text / divider</li>
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
    DIVIDER;

    /** 是否需要 options 数组（select / radio / checkbox） */
    public boolean needsOptions() {
        return this == SELECT || this == RADIO || this == CHECKBOX;
    }

    /** 是否需要 validator（min/max/pattern） */
    public boolean needsValidator() {
        return this == TEXT || this == TEXTAREA || this == NUMBER;
    }
}