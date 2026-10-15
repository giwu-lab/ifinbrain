package com.anomalyco.ifinbrain.web.design.form.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 表格字段的**查询数据**（绑了查询的表格用来显示数据，不是让人填数据）。
 *
 * <p>为什么行是 {@code Map} 而不是 BI 那边的 {@code List<List<Object>>}：
 * BI 是图表消费，列宽固定所以用下标数组最省事；表单表格的列是**可配的**
 * （设计者能改列顺序、删列、加列），行必须按列 key 取值才不至于错位。
 * 而且这样它和表格字段本来的值形状一致 ——
 * {@code [ { name: '机票', amount: 1200 } ]} —— 前端拿到就能直接渲染，零转换。
 *
 * <p>行里的值原样透传，本层不做任何 String 化或类型转换 —— 怎么显示由前端的列类型
 * 决定（数字右对齐、日期走列类型），在这里先转成字符串反而把信息锁死了。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FormTableDataDto {

    /** 列定义（由查询结果集自动匹配而来） */
    private List<FormFieldColumn> columns;

    /** 数据行；每行是「列 key → 值」 */
    private List<Map<String, Object>> rows;

    /** 是否被行数截断（前端据此提示"只显示了前 N 行"） */
    private boolean truncated;

    /** 实际行数 */
    private int rowCount;
}