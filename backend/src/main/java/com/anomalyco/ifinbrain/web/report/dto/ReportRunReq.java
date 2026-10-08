package com.anomalyco.ifinbrain.web.report.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 报表取数请求（虚拟滚动分页）。
 *
 * <p>{@code offset} 是**已加载行数**，不是页码 —— 前端滚动增量加载时
 * 「第几页」会随 pageSize 配置变化，用 offset 表达更稳定（换 pageSize 不影响已加载的行）。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReportRunReq {

    /** 查询头参数值；name 必须是 query.params_schema 里声明过的 */
    private Map<String, String> params;

    /** 起始行（0-based） */
    @NotNull(message = "offset 不能为空")
    @Min(value = 0, message = "offset 不能为负")
    private Long offset;

    /** 本次取多少行；null 用报表配置的 pageSize，上限受 QueryExecutor.MAX_PAGE_SIZE 夹紧 */
    @Min(value = 1, message = "size 最小 1")
    @Max(value = 2000, message = "size 最大 2000")
    private Integer size;
}
