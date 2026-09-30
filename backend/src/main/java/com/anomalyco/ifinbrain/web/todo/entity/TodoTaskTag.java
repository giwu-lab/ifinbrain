package com.anomalyco.ifinbrain.web.todo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.Objects;

/**
 * 任务-标签 关联表。复合主键 (task_id, tag_id)，双 ON DELETE CASCADE
 * （删任务 / 删标签都会自动清理中间行），service.delete() 之后还要再扫一遍 sys_todo_tag
 * 把孤儿标签一并删除（v1.1.3 行为对齐）。
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
    @Column(name = "tag_id")
    private Long tagId;

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PK implements Serializable {
        private Long taskId;
        private Long tagId;

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(taskId, pk.taskId) && Objects.equals(tagId, pk.tagId);
        }
        @Override public int hashCode() { return Objects.hash(taskId, tagId); }
    }
}
