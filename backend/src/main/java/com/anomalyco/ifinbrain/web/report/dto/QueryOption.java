package com.anomalyco.ifinbrain.web.report.dto;

import com.anomalyco.ifinbrain.web.datamgmt.dto.QueryParamSchema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 设计器里「可绑定的数据源查询」选项。
 *
 * <p>刻意<b>不含 sqlTemplate</b> —— 设计器只需要知道「有哪些查询、它们要什么参数」，
 * SQL 文本应该留在「数据管理 → 查询」里看。少一个 SQL 泄露面。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class QueryOption {

    private Long id;
    private String name;
    private String description;

    /** 该查询的参数 schema：设计器据此自动生成查询头 */
    private List<QueryParamSchema> params;
}
