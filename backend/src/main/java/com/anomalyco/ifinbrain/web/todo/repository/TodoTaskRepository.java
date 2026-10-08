package com.anomalyco.ifinbrain.web.todo.repository;

import com.anomalyco.ifinbrain.web.todo.entity.TodoTask;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TodoTaskRepository extends JpaRepository<TodoTask, Long>, JpaSpecificationExecutor<TodoTask> {

    Optional<TodoTask> findByIdAndAssigneeId(Long id, Long assigneeId);

    boolean existsByIdAndAssigneeId(Long id, Long assigneeId);

    /**
     * 当前用户的 PENDING 任务数 —— dashboard「我的任务」卡片用。
     * 与 service.dashboard() 的过滤条件一致。
     */
    long countByAssigneeIdAndStatus(Long assigneeId, String status);

    /** 全员某状态任务数（admin view-all 用）。Spring Data 派生查询。 */
    long countByStatus(String status);

    /** 父任务下拉数据源：当前用户的 PENDING 任务。 */
    List<TodoTask> findByAssigneeIdAndStatusOrderByIdDesc(Long assigneeId, String status);

    /**
     * 列表页的动态查询走 {@link JpaSpecificationExecutor#findAll}（见 TodoTaskService#page）：
     * assignee 隔离 + status / priority / keyword 全部下推到 SQL 的 WHERE，
     * 由 Spring Data 出正确的 total 与分页，排序用 {@code JpaSort.unsafe} 同样下推。
     */

    /**
     * 仪表盘卡片：当前用户的 PENDING 任务（带排序）。
     * 排序：逾期在前 → 按 due_at ASC → 无 due_at 殿后 → id DESC（最新在前）。
     */
    @Query("""
        SELECT t FROM TodoTask t
         WHERE t.assigneeId = :assigneeId
           AND t.status  = 'PENDING'
         ORDER BY
           CASE WHEN t.dueAt IS NULL THEN 1 ELSE 0 END ASC,
           CASE WHEN t.dueAt < :now THEN 0 ELSE 1 END ASC,
           t.dueAt ASC,
           t.id DESC
        """)
    List<TodoTask> findDashboard(@Param("assigneeId") Long assigneeId,
                                 @Param("now") LocalDateTime now,
                                 Pageable pageable);

    @Modifying
    @Query("""
        UPDATE TodoTask t
           SET t.status = 'COMPLETED',
               t.completedAt = :completedAt,
               t.updatedAt = :completedAt
         WHERE t.id = :id AND t.assigneeId = :assigneeId
        """)
    int markCompleted(@Param("id") Long id,
                      @Param("assigneeId") Long assigneeId,
                      @Param("completedAt") LocalDateTime completedAt);

    // ================= 四象限视图（仪表盘「我的任务」） =================

    /**
     * 四象限的数据源：我的全部 PENDING 任务。
     *
     * <p>排序即「象限内的默认排序」：有截止时间的排前且升序 → 无截止日期殿后 → id 倒序。
     * 已逾期因为 dueAt 更早而自然排到最前，不需要额外的 CASE WHEN。
     *
     * <p>象限归属（Q1~Q4）**不在 SQL 里算** —— 它是 priority × dueAt 的实时推导，
     * 写死在 JPQL 里会与 TodoQuadrant.of() 的规则分叉。见 {@link com.anomalyco.ifinbrain.web.todo.dto.TodoQuadrant}。
     */
    @Query("""
        SELECT t FROM TodoTask t
         WHERE t.assigneeId = :assigneeId
           AND t.status = 'PENDING'
         ORDER BY CASE WHEN t.dueAt IS NULL THEN 1 ELSE 0 END ASC,
                  t.dueAt ASC,
                  t.id DESC
        """)
    List<TodoTask> findPendingForQuadrant(@Param("assigneeId") Long assigneeId, Pageable pageable);

    /** 今日到期（截止日 = 今天）的 PENDING 数。 */
    @Query("""
        SELECT COUNT(t) FROM TodoTask t
         WHERE t.assigneeId = :assigneeId
           AND t.status = 'PENDING'
           AND t.dueAt >= :startOfDay
           AND t.dueAt < :startOfTomorrow
        """)
    long countDueToday(@Param("assigneeId") Long assigneeId,
                       @Param("startOfDay") LocalDateTime startOfDay,
                       @Param("startOfTomorrow") LocalDateTime startOfTomorrow);

    /**
     * 已逾期的 PENDING 数。
     *
     * <p>按**自然日**判定（{@code dueAt < 今日 00:00}），与前端 dueLabel / dueClass 的
     * 日期比较保持一致。刻意不用 {@code dueAt < :now} 精确比较：那样一条"今天 10:38 到期、
     * 现在 10:55"的任务会被统计条算作逾期，卡片却显示"今日到期"，同一页自相矛盾。
     */
    @Query("""
        SELECT COUNT(t) FROM TodoTask t
         WHERE t.assigneeId = :assigneeId
           AND t.status = 'PENDING'
           AND t.dueAt < :startOfToday
        """)
    long countOverdue(@Param("assigneeId") Long assigneeId, @Param("startOfToday") LocalDateTime startOfToday);

    /** 我的全部任务数（含已完成），作完成率分母。 */
    long countByAssigneeId(Long assigneeId);

    /**
     * 未完成的**直接**子任务数 —— 「子任务未完成则父任务不可勾选完成」的判定依据。
     *
     * <p>刻意只查直接子任务而不是整棵后代树：规则沿树传递（A 要完成需 B 完成，
     * B 要完成需 C 完成 ⇒ A 间接要求 C 完成），逐层拦即可覆盖整棵树，
     * 递归 CTE 反而在深树上更难读也更慢。Status 枚举只有 PENDING / COMPLETED 两种，
     * 所以「未完成」等价于 status = 'PENDING'。
     */
    long countByParentIdAndStatus(Long parentId, String status);

    /**
     * 「今天」tab：逾期 + 今日到期的 PENDING 任务，按截止时间升序。
     * {@code before} 取当天 23:59:59.999，天然把逾期的一并包进来。
     */
    @Query("""
        SELECT t FROM TodoTask t
         WHERE t.assigneeId = :assigneeId
           AND t.status = 'PENDING'
           AND t.dueAt IS NOT NULL
           AND t.dueAt < :beforeEndOfToday
         ORDER BY t.dueAt ASC, t.id DESC
        """)
    List<TodoTask> findTodayOrOverdue(@Param("assigneeId") Long assigneeId,
                                     @Param("beforeEndOfToday") LocalDateTime beforeEndOfToday,
                                     Pageable pageable);

    /**
     * 批量统计直接子任务的完成进度 —— 四象限卡片上的「子任务 0/2 · 50%」。
     *
     * <p>返回 Object[]{parentId, total, done} 三元组。必须批量：卡片列表若逐行查子任务，
     * 一屏 20 张卡就是 20 次查询。
     */
    @Query("""
        SELECT t.parentId, COUNT(t), SUM(CASE WHEN t.status = 'COMPLETED' THEN 1 ELSE 0 END)
          FROM TodoTask t
         WHERE t.parentId IN :parentIds
         GROUP BY t.parentId
        """)
    List<Object[]> countChildProgress(@Param("parentIds") java.util.Collection<Long> parentIds);

    /**
     * 直接子任务全量列表（含已完成）—— 父任务卡片展开后的明细。
     *
     * <p>刻意不限 assigneeId、也不限 status：子任务可能派给别人，也可能已完成，
     * 展开后要能看见完整的一棵树。是否可见由 service 侧按父任务的读权限统一裁决，
     * 避免出现「父任务能看、子任务列表却是空的」这种自相矛盾的界面。
     *
     * <p>排序：未完成优先 → 有截止时间的在前 → id 倒序，与列表页 LIST_SORT 的意图一致。
     */
    @Query("""
        SELECT t FROM TodoTask t
         WHERE t.parentId = :parentId
         ORDER BY (CASE WHEN t.status = 'COMPLETED' THEN 1 ELSE 0 END) ASC,
                  (CASE WHEN t.dueAt IS NULL THEN 1 ELSE 0 END) ASC,
                  t.dueAt ASC, t.id DESC
        """)
    List<TodoTask> findChildren(@Param("parentId") Long parentId);

    // ================= 洞察视图 =================

    /**
     * 洞察视图的数据源：当前执行人的全部任务（含已完成）。
     *
     * <p><b>刻意不在 SQL 里做分组聚合</b>，而是取全量回内存算：象限归属是
     * priority × dueAt 的实时推导（见 {@link com.anomalyco.ifinbrain.web.todo.dto.TodoQuadrant}），
     * SQL 里复刻这套判定就得写一堆 CASE WHEN，且一旦阈值改动（比如紧急线从 3 天调成 5 天）
     * SQL 和内存两处都得改，迟早会对不上。个人任务量在几千条以内，一次全量拉完全可接受。
     *
     * <p>取 assignee = me 而非全库：洞察是对着自己节奏的复盘，混入别人的任务没有意义
     * （与 /dashboard、/quadrant、/today 保持同一口径）。
     */
    @Query("""
        SELECT t FROM TodoTask t
         WHERE t.assigneeId = :assigneeId
         ORDER BY t.id DESC
        """)
    List<TodoTask> findAllForInsight(@Param("assigneeId") Long assigneeId);
}