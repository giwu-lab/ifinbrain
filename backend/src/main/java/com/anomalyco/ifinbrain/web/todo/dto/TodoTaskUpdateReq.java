package com.anomalyco.ifinbrain.web.todo.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Update 走 PUT 全量替换语义：未传字段置 null（与 invoice / documents 风格一致）。
 * title / priority / status / recurrence 必传（业务必填）。
 * tagNames 与 parentId 在保存时按"全量替换"处理。
 * assigneeId 可改；改 assignee 需 admin（todo:task:assign）或任务创建者。
 */
@Data
public class TodoTaskUpdateReq {

    @Size(max = 200)
    private String title;

    private String description;

    /** HIGH / MEDIUM / LOW / NONE */
    private String priority;

    /** PENDING / COMPLETED */
    private String status;

    /** NONE / DAILY / WEEKLY / MONTHLY / YEARLY */
    private String recurrence;

    /** WEEKLY: 1-7 ISO 周几；MONTHLY/YEARLY: 1-31（0 = 最后一天，仅 MONTHLY）；YEARLY 还需要 recurrenceMonth */
    private Integer recurrenceDow;
    private Integer recurrenceDom;
    private Integer recurrenceMonth;

    /** 接受 ISO 8601 格式（yyyy-MM-dd'T'HH:mm:ss），与 TodoTaskDto 输出格式一致。 */
    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime dueAt;

    /** 显式给值；null 表示清空父任务。 */
    private Long parentId;

    /** null 表示保留现有标签；空列表表示清空；非空表示新全集。 */
    private List<String> tagNames;

    /**
     * 执行人 id。null 表示不修改；非 null 表示改执行人。
     * 改 assignee 需：admin (todo:task:assign) 或 任务创建者 (createdBy = currentUser)。
     */
    private Long assigneeId;
}