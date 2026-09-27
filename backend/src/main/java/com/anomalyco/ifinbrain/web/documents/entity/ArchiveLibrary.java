package com.anomalyco.ifinbrain.web.documents.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 档案库（原「类目」）。
 * 一个档案库 = 一个左侧菜单项 + 一个 storage 前缀（documents/{slug}/） + 4 个专属 perm code。
 * 表名保留 sys_document_category 以兼容现有数据；类名 ArchiveLibrary 用于业务表达。
 */
@Entity
@Table(name = "sys_document_category")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArchiveLibrary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    /** URL 段（kebab-case），全表 UNIQUE。决定子菜单 path = /documents/{slug}。 */
    @Column(nullable = false, length = 64, unique = true)
    private String slug;

    @Column(nullable = false, length = 64)
    private String icon;

    @Column(nullable = false)
    private Integer sort;

    /** 0 = 禁用；1 = 启用。 */
    @Column(nullable = false)
    private Short status;

    @Column(length = 255)
    private String remark;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) this.status = 1;
        if (this.icon == null) this.icon = "Folder";
        if (this.sort == null) this.sort = 0;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
