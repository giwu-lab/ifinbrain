package com.anomalyco.ifinbrain.web.report.dto;

import com.anomalyco.ifinbrain.web.datamgmt.dto.QueryResultDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 报表取数响应（一页）。
 *
 * <p>刻意<b>不含 total 总行数</b>：算总数要再跑一次 SELECT count(*)，大表上成本翻倍，
 * 而虚拟滚动只关心「还有没有下一页」。需要全量导出的场景走 iterateRows 流式，不经过这里。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReportRunResp {

    /** 列元数据 [{key,label,type}] */
    private List<QueryResultDto.Column> columns;

    /** 本次返回的行；每行顺序对齐 columns */
    private List<List<Object>> rows;

    private Long offset;
    private Integer size;

    /** 还有下一页吗（后端多读 1 行判定，不是 COUNT(*)） */
    private Boolean hasMore;

    /** 是否被报表的 max_rows 护栏截住；true 表示再滚也不会有新数据 */
    private Boolean capped;

    /** 下一次请求该用的 offset */
    private Long nextOffset;

    private Long durationMs;
    private Integer timeoutSec;
}
