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