package com.anomalyco.ifinbrain.web.todo.repository;

import com.anomalyco.ifinbrain.web.todo.entity.TodoTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TodoTaskRepository extends JpaRepository<TodoTask, Long> {

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
     * 默认列表：当前用户的任务（assignee = me）。admin 走 searchAll（绕过过滤）。
     */
    @Query("""
        SELECT t FROM TodoTask t
         WHERE t.assigneeId = :assigneeId
        """)
    Page<TodoTask> searchByAssignee(@Param("assigneeId") Long assigneeId, Pageable pageable);

    /**
     * 跨用户全员视图（admin 用）。按原过滤条件在内存层做。
     */
    @Query("""
        SELECT t FROM TodoTask t
        """)
    Page<TodoTask> searchAll(Pageable pageable);

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
}