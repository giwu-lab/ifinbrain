package com.anomalyco.ifinbrain.web.datamgmt.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 查询执行结果。前端按 columns 渲表头、按 rows 渲数据。
 * rows 是 List<List<Object>> 而非 List<Map>：保留列顺序，且前端列序号与 cell 序号一致。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class QueryResultDto {

    /** 列元数据：[{key, label, type}]；key 即 SELECT 里的列名 */
    private List<Column> columns;

    /** 数据行：每行一个 List，顺序对齐 columns */
    private List<List<Object>> rows;

    /** 实际返回的行数（<= limit 截断值） */
    private int rowCount;

    /** 是否被行数截断（true 表示结果可能有遗漏） */
    private boolean truncated;

    /** 服务端执行耗时（毫秒） */
    private long durationMs;

    /** 行数上限，方便前端显示 */
    private int rowLimit;

    /** 超时上限（秒），方便前端显示 */
    private int timeoutSec;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Column {
        /** 原始列名（DB 返回） */
        private String key;
        /** 友好列名（通常就是 key，但 PG 大写时会被 lower） */
        private String label;
        /** 推测的列类型：string | number | date | datetime | boolean | unknown */
        private String type;
        /** 列宽（px）；null = 自动。报表设计时由 design 列属性写入 */
        private Integer width;
        /** 对齐：left | center | right。报表设计时由 design 列属性写入 */
        private String align;
    }
}