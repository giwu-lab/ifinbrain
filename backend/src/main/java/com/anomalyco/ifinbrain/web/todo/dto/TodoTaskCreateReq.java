package com.anomalyco.ifinbrain.web.todo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class TodoTaskCreateReq {

    @NotBlank
    @Size(max = 200)
    private String title;

    private String description;

    /** HIGH / MEDIUM / LOW / NONE；缺省 NONE */
    private String priority;

    /** NONE / DAILY / WEEKLY / MONTHLY / YEARLY；缺省 NONE */
    private String recurrence;

    /** WEEKLY: 1-7 ISO 周几；MONTHLY/YEARLY: 1-31（0 = 最后一天，仅 MONTHLY）；YEARLY 还需要 recurrenceMonth */
    private Integer recurrenceDow;
    private Integer recurrenceDom;
    private Integer recurrenceMonth;

    /** 接受 yyyy-MM-dd HH:mm:ss；空字符串视作 null。 */
    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime dueAt;

    /** 父任务 id（同 assignee + PENDING）。可空。 */
    private Long parentId;

    /**
     * 标签名列表。service 按名 upsert 到 sys_todo_tag 并建立关联。
     * 标签记在「任务执行人（assignee）」名下：admin 创建给 B 时，标签归 B。
     * 空字符串 / 空白会被跳过；同名视为同一标签。
     */
    private List<String> tagNames;

    /**
     * 执行人 id。可空 —— 默认当前用户。
     * 只有 admin（持 todo:task:assign）或任务创建者能改；其他情况会被 service 忽略（保持当前用户）。
     */
    private Long assigneeId;
}