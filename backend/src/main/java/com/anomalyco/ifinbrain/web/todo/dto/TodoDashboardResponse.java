package com.anomalyco.ifinbrain.web.todo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 仪表盘 widget 数据源响应：
 *  - items: 展示列表（默认前 10 条，受 limit 限制）
 *  - total : 当前用户 PENDING 任务真实总数（可能 > items.length）
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TodoDashboardResponse {

    private List<TodoDashboardItem> items;
    private long total;
}