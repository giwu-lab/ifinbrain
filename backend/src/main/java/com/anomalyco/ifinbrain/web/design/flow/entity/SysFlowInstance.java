package com.anomalyco.ifinbrain.web.design.flow.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "sys_flow_instance")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SysFlowInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flow_id", nullable = false)
    private Long flowId;

    @Column(name = "flow_code", nullable = false, length = 64)
    private String flowCode;

    @Column(name = "business_key", nullable = false, length = 128)
    private String businessKey;

    /** RUNNING / APPROVED / REJECTED / CANCELED */
    @Column(nullable = false, length = 32)
    private String state;

    @Column(name = "current_node_id", length = 64)
    private String currentNodeId;

    /** 流程变量 JSON（含表单数据 + 系统变量） */
    @Column(name = "variables_json", columnDefinition = "TEXT")
    private String variablesJson;

    @Column(name = "started_by", length = 64)
    private String startedBy;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @PrePersist
    void onCreate() {
        if (this.startedAt == null) this.startedAt = LocalDateTime.now();
    }
}