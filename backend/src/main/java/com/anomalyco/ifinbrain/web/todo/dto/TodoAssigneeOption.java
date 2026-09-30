package com.anomalyco.ifinbrain.web.todo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 执行人选择器选项：admin 创建/重指派任务时的下拉数据源。
 * 仅返回启用用户，字段最小化（id + displayName + username）。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TodoAssigneeOption {
    private Long id;
    private String username;
    private String displayName;
}