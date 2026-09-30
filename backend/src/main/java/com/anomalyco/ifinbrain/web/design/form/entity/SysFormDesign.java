package com.anomalyco.ifinbrain.web.design.form.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "sys_form_design")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SysFormDesign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务码（kebab-case），如 'leave_request'；全局唯一 */
    @Column(nullable = false, length = 64, unique = true)
    private String code;

    /** 显示名 */
    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    /**
     * 表单元数据 JSON：
     * { "version": 1, "fields": [ FormFieldSchema... ] }
     */
    @Lob
    @Column(name = "fields_json", nullable = false, columnDefinition = "TEXT")
    private String fieldsJson;

    /** 0=草稿 / 1=已发布 */
    @Column(nullable = false)
    private Short status;

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
        if (this.fieldsJson == null || this.fieldsJson.isBlank()) {
            this.fieldsJson = "{\"version\":1,\"fields\":[]}";
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}