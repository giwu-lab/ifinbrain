package com.anomalyco.ifinbrain.web.design.flow.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "sys_flow_history")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SysFlowHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Column(name = "node_label", length = 128)
    private String nodeLabel;

    /** SUBMIT / APPROVE / REJECT / DELEGATE / JUMP */
    @Column(nullable = false, length = 16)
    private String action;

    @Column(nullable = false, length = 64)
    private String operator;

    @Column(name = "operator_name", length = 128)
    private String operatorName;

    @Column(length = 512)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
    }
}