package com.anomalyco.ifinbrain.web.todo.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 洞察视图响应。
 *
 * <p>回答两个问题：<b>我的节奏如何</b>（趋势 + 指标），<b>我的任务结构健康吗</b>（象限分布 + 建议）。
 *
 * <p>口径全部限定 assignee = me —— 洞察是对着自己节奏的复盘，混入他人任务会让
 * 完成率和逾期率都失去参照意义（与 quadrant / today / dashboard 同一口径）。
 */
@Data
@Builder
public class TodoInsightResponse {

    /** 本次统计的天数窗口：7 或 30。前端切换时重新请求。 */
    private int days;

    // ================= 指标卡 =================

    /** 完成率 = 窗口内完成数 / 窗口内新建数。分母为 0 时为 0。 */
    private double completionRate;
    /** 逾期率 = 逾期未完成 / 未完成总数。分母为 0 时为 0。 */
    private double overdueRate;
    /**
     * 平均完成耗时（小时）—— createdAt → completedAt 的均值。
     * 只统计窗口内完成且两端时间都存在的任务；无样本时为 null（前端显示「—」而不是 0，
     * 「0 小时」会被误读成"瞬间完成"）。
     */
    private Double avgCycleHours;
    /** 累计预估投入（小时）—— 窗口内所有非空 estimateMinutes 之和。 */
    private double totalEstimateHours;
    /** 有预估时长的任务占比（0~1），反映估算习惯的覆盖度。 */
    private double estimatedCoverage;
    /** 当前未完成积压（不含窗口过滤，全量口径）。 */
    private int backlog;
    /** 窗口内新增数。 */
    private int createdInRange;
    /** 窗口内完成数。 */
    private int completedInRange;

    // ================= 趋势 =================

    /** 按天的完成/新增序列，长度恒等于 days（缺失日补 0，否则折线会跳日期）。 */
    private List<TrendPoint> trend;

    // ================= 结构 =================

    /** 四个象限的未完成任务分布（含标签、建议时长）。 */
    private List<QuadrantShare> quadrants;

    // ================= 行动建议 =================

    /** 最多 3 条，按严重度排序。 */
    private List<Suggestion> suggestions;

    /** 趋势上的一个点。date 是本地日期，序列按日期升序。 */
    @Data
    @Builder
    public static class TrendPoint {
        private LocalDate date;
        /** 当天完成的任务数。 */
        private int completed;
        /** 当天新建的任务数。 */
        private int created;
    }

    /** 象限结构占比。count 是该象限当前未完成数。 */
    @Data
    @Builder
    public static class QuadrantShare {
        private String key;
        private long count;
        /** 该象限内所有非空 estimateMinutes 之和（分钟）。 */
        private long estimateMinutes;
    }

    /**
     * 一条行动建议。
     *
     * <p>kind 决定前端图标与配色：{@code backlog} 积压 / {@code overdue} 逾期 / {@code q4} 可删减
     * / {@code q2} 长期未推进 / {@code balanced} 状态良好。
     */
    @Data
    @Builder
    public static class Suggestion {
        private String kind;
        /** 建议标题（已本地化）。 */
        private String title;
        /** 建议正文（已本地化）。 */
        private String detail;
        /** 严重度 0~100，用于排序和强调。 */
        private int severity;
    }
}
