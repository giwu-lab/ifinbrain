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

    /**
     * 截止时间。只接受 ISO 8601 带 T 的格式：{@code 2026-10-07T18:00:00}。
     *
     * <p>注意这里曾写着"接受 yyyy-MM-dd HH:mm:ss"（空格），而 pattern 实际是 T ——
     * 注释与实现不符，前端照着注释写了空格格式，导致新建带截止时间的任务必然 400。
     * 已按 pattern 校正，别再改回去。
     */
    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime dueAt;

    /** 预估投入时长（分钟）。可空 = 未估；0 = 已估但为 0。 */
    @jakarta.validation.constraints.Min(0)
    private Integer estimateMinutes;

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