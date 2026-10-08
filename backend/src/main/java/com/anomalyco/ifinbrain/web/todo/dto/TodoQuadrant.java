package com.anomalyco.ifinbrain.web.todo.dto;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 四象限归类：重要度 × 紧急度。
 *
 * <p>借用经典艾森豪威尔矩阵，但**判定刻意从简**，以适配本项目已有的数据：
 *
 * <pre>
 *            紧急（逾期或 ≤3 天到期）     不紧急
 *   重要       Q1 立即做                  Q2 计划做
 *   不重要     Q3 委托做                  Q4 减少做
 * </pre>
 *
 * <h3>为什么重要度只取 HIGH</h3>
 * {@code TodoTask.Priority} 有 HIGH/MEDIUM/LOW/NONE 四档，但四象限只有两个维度。
 * 若把 HIGH+MEDIUM 都算「重要」，Q2 会被 MEDIUM 任务淹没，失去"要长期投入"的意义；
 * 若把 MEDIUM 也算「不重要」，又会让大量正常任务被打上"该拒绝"的标签。
 * 所以 <b>重要 = HIGH</b>，其余三档一律归入「不重要」侧，再靠紧急度分流到 Q3 / Q4。
 *
 * <h3>紧急阈值为 3 天</h3>
 * 3 天是"还来得及安排"的临界线：3 天内要做的事已经无法靠排期化解，必须立刻处理。
 *
 * <p><b>不落库</b>：紧急度每天都在变，今天到期明天就不急了。落库需要配套定时刷新，
 * 且刷新前后的数据自相矛盾。改为每次查询实时推导。
 */
public enum TodoQuadrant {

    /** 重要且紧急 —— 立即做，集中精力完成。 */
    Q1("Q1"),
    /** 重要不紧急 —— 计划做，预留时间持续推进长期价值。 */
    Q2("Q2"),
    /** 紧急不重要 —— 委托做，交给合适的人并跟进结果。 */
    Q3("Q3"),
    /** 不重要不紧急 —— 减少做，删除、简化或有意忽略。 */
    Q4("Q4");

    private final String key;
    TodoQuadrant(String key) { this.key = key; }
    public String getKey() { return key; }

    /** 距截止日 ≤ 此天数即视为紧急。 */
    public static final int URGENT_WITHIN_DAYS = 3;

    /**
     * 归类一个任务。
     *
     * @param priority HIGH / MEDIUM / LOW / NONE
     * @param dueAt    截止时间，可空（无截止日期 = 不紧急）
     * @param now      判定基准时间
     * @return 所属象限；status 为 COMPLETED 或 priority/status 非预期值时返回 null
     */
    public static TodoQuadrant of(String priority, LocalDateTime dueAt, LocalDateTime now) {
        boolean important = "HIGH".equals(priority);
        boolean urgent = isUrgent(dueAt, now);
        if (important) {
            return urgent ? Q1 : Q2;
        }
        return urgent ? Q3 : Q4;
    }

    /**
     * 紧急 = 已逾期，或距今 ≤ {@link #URGENT_WITHIN_DAYS} 天到期。
     *
     * <p>注意用整天数（ChronoUnit.DAYS）而非精确毫秒：同一分钟内到期的任务不应
     * 因为执行时间的毫秒差被判成"不紧急"。
     */
    public static boolean isUrgent(LocalDateTime dueAt, LocalDateTime now) {
        if (dueAt == null) return false;          // 无截止日期 = 不紧急
        return !dueAt.isAfter(now.plusDays(URGENT_WITHIN_DAYS));
    }

    /**
     * 是否已逾期（截止日期早于今天）。
     *
     * <p>按**自然日**判定而非精确时刻："今天 10:38 到期、现在 10:55"仍算今天到期，
     * 与前端 dueLabel / dueClass 的日期比较一致。统计条与任务卡片若一个按时刻、
     * 一个按日期，同一条任务会在同一页上显示成两种状态。
     */
    public static boolean isOverdue(LocalDateTime dueAt, LocalDateTime now) {
        return dueAt != null && dueAt.toLocalDate().isBefore(now.toLocalDate());
    }

    /** 今日到期（同一自然日，含已逾期）。 */
    public static boolean isDueToday(LocalDateTime dueAt, LocalDateTime now) {
        return dueAt != null && dueAt.toLocalDate().equals(now.toLocalDate());
    }

    /** 距 dueAt 相差的天数（负数=已逾期），用于统计条的口径说明。 */
    public static long daysUntil(LocalDateTime dueAt, LocalDateTime now) {
        return dueAt == null ? 0 : ChronoUnit.DAYS.between(now.toLocalDate(), dueAt.toLocalDate());
    }
}
