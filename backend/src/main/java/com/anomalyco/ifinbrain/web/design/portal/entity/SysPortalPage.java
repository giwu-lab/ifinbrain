package com.anomalyco.ifinbrain.web.design.portal.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "sys_portal_page")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SysPortalPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务唯一键：URL 片段 / portal resolve 用 */
    @Column(nullable = false, length = 64, unique = true)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    /**
     * 整页 layout JSON：
     * { version, root: { id, type:'page', props, children: [...] } }
     * children 中每个节点可携带 layout:{x,y,w,h} 给 grid-layout-plus 用。
     */
    @Lob
    @Column(name = "layout_schema", nullable = false, columnDefinition = "TEXT")
    private String layoutSchema;

    @Column(name = "config_version", nullable = false)
    private Integer configVersion;

    /** 0=draft / 1=published */
    @Column(nullable = false)
    private Short status;

    /** JSON 数组：['admin','operator',...] */
    @Lob
    @Column(name = "role_bindings", nullable = false, columnDefinition = "TEXT")
    private String roleBindings;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) this.status = 0;
        if (this.configVersion == null) this.configVersion = 1;
        if (this.roleBindings == null) this.roleBindings = "[]";
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}