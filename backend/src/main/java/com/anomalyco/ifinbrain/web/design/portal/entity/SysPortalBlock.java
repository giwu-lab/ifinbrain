package com.anomalyco.ifinbrain.web.design.portal.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "sys_portal_block")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SysPortalBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 注册表 key（stat-card / chart-card / data-table / quick-links / text-block / iframe / page / row / col） */
    @Column(nullable = false, length = 64, unique = true)
    private String type;

    @Column(nullable = false, length = 64)
    private String name;

    /** stat / chart / table / media / layout */
    @Column(nullable = false, length = 32)
    private String category;

    @Lob
    @Column(name = "default_config", nullable = false, columnDefinition = "TEXT")
    private String defaultConfig;

    /**
     * config_schema JSON：
     * { fields: [ { key, label, type: 'string'|'number'|'boolean'|'select'|'textarea',
     *               default, options?:[{label,value}], placeholder?, required? } ] }
     * type=select 时 options 必填；其他可选。
     */
    @Lob
    @Column(name = "config_schema", nullable = false, columnDefinition = "TEXT")
    private String configSchema;

    @Column(nullable = false, length = 64)
    private String icon;

    @Column(nullable = false)
    private Integer sort;

    @Column(nullable = false)
    private Short enabled;

    /** 1=内置不可删 / 0=用户扩展 */
    @Column(name = "built_in", nullable = false)
    private Short builtIn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.enabled == null) this.enabled = 1;
        if (this.builtIn == null) this.builtIn = 0;
        if (this.sort == null) this.sort = 0;
        if (this.icon == null) this.icon = "Grid";
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}