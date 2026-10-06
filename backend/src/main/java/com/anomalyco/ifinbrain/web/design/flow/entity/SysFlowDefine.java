package com.anomalyco.ifinbrain.web.design.flow.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "sys_flow_define")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SysFlowDefine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务码（kebab-case），全局唯一 */
    @Column(nullable = false, length = 64, unique = true)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    /**
     * 流程图 JSON：{ "nodes":[...], "edges":[...] }
     * 节点类型见 NodeType（START / APPROVAL / CONDITION / END）。
     */
    @Column(name = "flow_json", nullable = false, columnDefinition = "TEXT")
    private String flowJson;

    /** 流程默认挂的表单 code（可选）；节点也可单独挂自己的 formCode 覆盖默认 */
    @Column(name = "default_form_code", length = 64)
    private String defaultFormCode;

    /** 0=草稿 / 1=已发布 */
    @Column(nullable = false)
    private Short status;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime n = LocalDateTime.now();
        this.createdAt = n;
        this.updatedAt = n;
        if (this.status == null) this.status = 0;
        if (this.version == null) this.version = 1;
        if (this.flowJson == null || this.flowJson.isBlank()) {
            this.flowJson = "{\"nodes\":[],\"edges\":[]}";
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}