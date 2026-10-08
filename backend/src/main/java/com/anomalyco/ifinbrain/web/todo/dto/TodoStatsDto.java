package com.anomalyco.ifinbrain.web.todo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 四象限视图顶部的统计条。
 *
 * <p>口径说明（写在 javadoc 里，免得半年后没人记得完成率的分母是什么）：
 * <ul>
 *   <li>{@code pending}      —— 我的 PENDING 总数（四象限四个格子加起来就是这个数）</li>
 *   <li>{@code dueToday}     —— 截止日期落在今天的 PENDING 数</li>
 *   <li>{@code overdue}      —— 已逾期的 PENDING 数（严格早于当前时间）</li>
 *   <li>{@code completionRate} —— 已完成 / 全部任务，取值 0~1，前端乘 100 展示。
 *       用全量口径而非"近 7 天"，因为近 7 天口径在低频使用时会剧烈跳变。</li>
 * </ul>
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TodoStatsDto {

    /** 我的 PENDING 任务数。 */
    private long pending;
    /** 今日到期（截止日 = 今天）的 PENDING 数。 */
    private long dueToday;
    /** 已逾期的 PENDING 数。 */
    private long overdue;
    /** 已完成任务数（分母口径见类注释）。 */
    private long completed;
    /** 全部任务数（我的，含已完成）。 */
    private long total;
    /** 完成率 0~1。total 为 0 时返回 0，而不是 NaN。 */
    private double completionRate;
}
