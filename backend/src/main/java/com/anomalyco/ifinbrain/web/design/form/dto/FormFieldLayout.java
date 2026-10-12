package com.anomalyco.ifinbrain.web.design.form.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 字段在 24 列栅格中的位置（口径与 el-form 的 :span 一致）。
 *
 * <p><b>坐标语义</b>：x / y 均 0-based；y <b>没有上界</b> —— 与 BI 大屏的定尺画布不同，
 * 表单高度随字段数增长，纵向滚动。
 *
 * <p>留空表示「还没排版」，加载时由前端按老的 fullWidth 语义迁移补齐。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FormFieldLayout {

    /** 起始列，0-based */
    private Integer x;

    /** 起始行，0-based，无上界 */
    private Integer y;

    /** 跨列数，1..24 */
    private Integer w;

    /** 跨行数，≥1 */
    private Integer h;
}