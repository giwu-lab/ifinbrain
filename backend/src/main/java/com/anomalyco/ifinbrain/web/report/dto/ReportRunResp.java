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
 * <p>{@code total} 是给页码分页器（el-pagination）用的总行数，来源是 {@code QueryExecutor#countRows}
 * 包一层 {@code SELECT COUNT(*)}。拿不到时是 -1（方言不支持 / 超时），前端据此退化成
 * 「有下一页 / 没下一页」的判断，不影响翻页本身。
 *
 * <p>流式导出（CSV/XLSX）走 {@code iterateRows}，不经过这里，仍然与总数无关。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReportRunResp {

    /** 列元数据 [{key,label,type}] */
    private List<QueryResultDto.Column> columns;

    /** 本次返回的行；每行顺序对齐 columns */
    private List<List<Object>> rows;

    private Long offset;
    private Integer size;

    /** 总行数；-1 = 未知（count 失败），前端按 hasMore 退化处理 */
    private Long total;

    /** 还有下一页吗（后端多读 1 行判定，不是 COUNT(*)） */
    private Boolean hasMore;

    /** 是否被报表的 max_rows 护栏截住；true 表示再滚也不会有新数据 */
    private Boolean capped;

    /** 下一次请求该用的 offset */
    private Long nextOffset;

    private Long durationMs;
    private Integer timeoutSec;
}
