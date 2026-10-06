package com.anomalyco.ifinbrain.web.todo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务标签下拉选项。
 *
 * <p>V43 起标签实体就是 {@code sys_dict_item} 里 {@code dict_type='todo_tag'} 的条目，
 * 但本 DTO 只暴露 id + name 两个字段：前端标签选择器的 value 用的是<b>标签名</b>
 * 而不是 id（见 {@code views/todo/task/Index.vue} 的 el-option :value="t.name"），
 * 所以不把字典的 code/label/value/sort 等管理字段泄给 todo 前端。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TodoTagDto {
    private Long id;
    private String name;
}
