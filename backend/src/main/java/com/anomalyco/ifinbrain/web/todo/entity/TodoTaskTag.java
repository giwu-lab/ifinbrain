package com.anomalyco.ifinbrain.web.todo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.Objects;

/**
 * 任务-标签 关联表（V43 后标签来自 sys_dict_type='todo_tag'）。
 * 复合主键 (task_id, dict_item_id)，双 ON DELETE CASCADE。
 * dict_item_id 指向 sys_dict_item.id（即 todo_tag 字典条目）。
 */
@Entity
@Table(name = "sys_todo_task_tag")
@IdClass(TodoTaskTag.PK.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TodoTaskTag {

    @Id
    @Column(name = "task_id")
    private Long taskId;

    @Id
    @Column(name = "dict_item_id")
    private Long dictItemId;

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PK implements Serializable {
        private Long taskId;
        private Long dictItemId;

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(taskId, pk.taskId) && Objects.equals(dictItemId, pk.dictItemId);
        }
        @Override public int hashCode() { return Objects.hash(taskId, dictItemId); }
    }
}
