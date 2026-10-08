package com.anomalyco.ifinbrain.web.todo.dto;

import com.anomalyco.ifinbrain.web.todo.entity.TodoTask;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TodoTaskDto {

    private Long id;
    /** 任务执行人 id。 */
    private Long assigneeId;
    /** 执行人展示名（冗余字段避免 N+1 join user 表）。 */
    private String assigneeName;
    /** 任务创建者 id。admin 创建给其他人时与 assignee 不同。 */
    private Long createdBy;
    /** 创建者展示名。 */
    private String createdByName;
    private String title;
    private String description;
    /** HIGH / MEDIUM / LOW / NONE */
    private String priority;
    /** PENDING / COMPLETED */
    private String status;
    private LocalDateTime dueAt;
    /** 预估投入时长（分钟）。null = 未估，前端不渲染时长元素。 */
    private Integer estimateMinutes;
    private Long parentId;
    /** NONE / DAILY / WEEKLY / MONTHLY / YEARLY */
    private String recurrence;
    /** WEEKLY: 1-7 ISO 周几；MONTHLY/YEARLY: 1-31（0 = 最后一天，仅 MONTHLY）；YEARLY 还需要 recurrenceMonth */
    private Integer recurrenceDow;
    private Integer recurrenceDom;
    private Integer recurrenceMonth;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 当前任务的 tag id 列表；service 在列表 / 详情里回填。 */
    private List<Long> tagIds;
    /** 与 tagIds 等长；冗余 name 字段避免前端弹窗再次拉 tags。 */
    private List<String> tagNames;

    // ---- 按行的操作权限（service 在 enrich 阶段按当前登录用户填充）----
    //
    // 为什么不能只靠前端 auth.hasPerm('todo:task:update')：
    // 那是「角色级」权限，而任务能否被写还取决于「这条任务的执行人是否在本部门子树内」。
    // 一个 task_manager 在列表里会同时看到本部门任务和跨部门任务，两者的可写性不同，
    // 只能逐行判定 —— 否则就是「按钮能点、点完 403」。

    /** 当前用户能否编辑本行（改标题/优先级/截止时间等）。自己的任务恒为 true。 */
    private Boolean canEdit;
    /** 当前用户能否删除本行。自己的任务恒为 true。 */
    private Boolean canDelete;
    /** 当前用户能否完成本行（勾选完成框）。 */
    private Boolean canComplete;
    /**
     * 本行是否属于「跨部门只读」—— 即执行人在别的部门子树里。
     * 用于前端把操作按钮置灰并给出「跨部门任务，仅可查看」提示。
     * 非跨部门场景为 false。
     */
    private Boolean crossDeptReadOnly;

    // ---- 四象限视图（仪表盘「我的任务」）----
    //
    // quadrant **刻意不落库**：象限是「重要度 × 紧急度」的实时推导结果，
    // 而紧急度每天都在变（今天到期 → 明天就不急了）。落库会立刻过期，
    // 还得写定时任务去刷新。由 TodoQuadrant.of(priority, dueAt, now) 现算。

    /** Q1 立即做 / Q2 计划做 / Q3 委托做 / Q4 减少做；已完成任务为 null。 */
    private String quadrant;
    /** 直接子任务总数 —— 卡片上的「子任务 0/2」。 */
    private Integer childTotal;
    /** 已完成的直接子任务数 —— 卡片上的进度百分比。 */
    private Integer childDone;
    /**
     * 未完成的直接子任务数 —— 等于 childTotal - childDone。
     * 前端据此把父任务的勾选框置灰并提示「还有 N 个子任务未完成」，
     * 让 canComplete=false 的原因可区分：权限不足 vs 等待子任务。
     */
    private Integer pendingChildCount;

    public static TodoTaskDto fromEntity(TodoTask e, List<Long> tagIds, List<String> tagNames) {
        return TodoTaskDto.builder()
                .id(e.getId())
                .assigneeId(e.getAssigneeId())
                .createdBy(e.getCreatedBy())
                .title(e.getTitle())
                .description(e.getDescription())
                .priority(e.getPriority())
                .status(e.getStatus())
                .dueAt(e.getDueAt())
                .estimateMinutes(e.getEstimateMinutes())
                .parentId(e.getParentId())
                .recurrence(e.getRecurrence())
                .recurrenceDow(e.getRecurrenceDow())
                .recurrenceDom(e.getRecurrenceDom())
                .recurrenceMonth(e.getRecurrenceMonth())
                .completedAt(e.getCompletedAt())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .tagIds(tagIds)
                .tagNames(tagNames)
                .build();
    }
}