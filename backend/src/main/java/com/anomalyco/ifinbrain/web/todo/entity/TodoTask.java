package com.anomalyco.ifinbrain.web.todo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "sys_todo_task")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TodoTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务执行人（sys_user.id）。所有读查询都按 assignee_id 过滤。
     *  admin 可改；非 admin 用户创建的票可被创建者改，其他人不可改。 */
    @Column(name = "assignee_id", nullable = false)
    private Long assigneeId;

    /** 任务创建者（sys_user.id）。可能与 assignee 不同：admin 创建任务给别人。
     *  ON DELETE SET NULL —— admin 被删后任务仍保留，由 view-all 视图查看。 */
    @Column(name = "created_by")
    private Long createdBy;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** HIGH / MEDIUM / LOW / NONE */
    @Column(nullable = false, length = 16)
    private String priority;

    /** PENDING / COMPLETED */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "due_at")
    private LocalDateTime dueAt;

    /** 父任务（同 assignee + PENDING）；删除父任务时本字段被 SET NULL。 */
    @Column(name = "parent_id")
    private Long parentId;

    /** NONE / DAILY / WEEKLY / MONTHLY / YEARLY */
    @Column(nullable = false, length = 16)
    private String recurrence;

    /** WEEKLY: ISO 周几（1=周一 ~ 7=周日） */
    @Column(name = "recurrence_dow")
    private Integer recurrenceDow;

    /** MONTHLY: 1-31 月份第几天；YEARLY: 1-31。0 = 「最后一天」（仅 MONTHLY 语义）。 */
    @Column(name = "recurrence_dom")
    private Integer recurrenceDom;

    /** YEARLY: 1-12。WEEKLY/MONTHLY 不使用。 */
    @Column(name = "recurrence_month")
    private Integer recurrenceMonth;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.priority == null)   this.priority = Priority.NONE.name();
        if (this.status == null)     this.status = Status.PENDING.name();
        if (this.recurrence == null) this.recurrence = Recurrence.NONE.name();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum Priority   { HIGH, MEDIUM, LOW, NONE }
    public enum Status     { PENDING, COMPLETED }
    public enum Recurrence { NONE, DAILY, WEEKLY, MONTHLY, YEARLY }
}