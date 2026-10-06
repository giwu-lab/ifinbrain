package com.anomalyco.ifinbrain.web.bi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 大屏「可绑定查询」列表项。
 *
 * <p>刻意<b>不</b>复用 {@code QueryDto}：那边会带出 sqlTemplate / lastRun* 等字段，
 * 数据绑定面板只需要「能选哪个查询 + 它有哪些必填参数」。
 *
 * @param paramsSchema 参数 schema（来自 sys_query.params_schema_json），
 *                    前端据此判断哪些参数要预填、能不能取到列信息
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BiQueryOptionDto {

    private Long id;
    private String name;
    private String description;
    private Long datasourceId;
    private String datasourceName;
    private List<QueryParam> paramsSchema;

    /** 参数项。与 datamgmt 的 QueryParamSchema 同构，供前端做必填校验 / 取默认值。 */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class QueryParam {
        private String name;
        private String label;
        private String type;
        private Boolean required;
        private String defaultValue;
        private String placeholder;
    }
}