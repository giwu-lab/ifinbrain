package com.anomalyco.ifinbrain.web.todo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 仪表盘 widget 专用 DTO —— 只携带列表渲染必需的字段，避免暴露 description / 父链等。
 * dueLabel 由前端基于 dueAt 渲染（"今日到期" / "逾期 2 天" 等）。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TodoDashboardItem {

    private Long id;
    private String title;
    private String priority;
    private LocalDateTime dueAt;
    private String recurrence;
}
