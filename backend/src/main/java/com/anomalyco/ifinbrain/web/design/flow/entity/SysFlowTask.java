package com.anomalyco.ifinbrain.web.design.flow.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "sys_flow_task")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SysFlowTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Column(name = "flow_id", nullable = false)
    private Long flowId;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Column(name = "node_label", length = 128)
    private String nodeLabel;

    /** USER / ROLE / ORG */
    @Column(name = "assignee_type", nullable = false, length = 16)
    private String assigneeType;

    @Column(name = "assignee_id", nullable = false, length = 64)
    private String assigneeId;

    @Column(name = "assignee_name", length = 128)
    private String assigneeName;

    /** PENDING / APPROVED / REJECTED / SKIPPED */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 512)
    private String comment;

    /** 联动 todo_task.id（Phase 4 创建 todo 任务） */
    @Column(name = "todo_task_id")
    private Long todoTaskId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
    }
}