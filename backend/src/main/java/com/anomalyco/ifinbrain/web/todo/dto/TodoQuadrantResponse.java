package com.anomalyco.ifinbrain.web.todo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 「四象限」tab 的完整数据源：统计条 + 四个格子。
 *
 * <p>一次请求返回全部数据，前端不做二次聚合 —— 否则统计数字和格子内容
 * 会因为两次计算口径漂移而对不上（比如格子过滤掉了已完成、统计却算进去了）。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TodoQuadrantResponse {

    private TodoStatsDto stats;
    /** 恒为 4 项，顺序 Q1→Q2→Q3→Q4，对应 2×2 网格的左上→右上→左下→右下。 */
    private List<TodoQuadrantBucket> quadrants;
}
