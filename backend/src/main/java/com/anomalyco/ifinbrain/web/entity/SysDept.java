package com.anomalyco.ifinbrain.web.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 组织架构部门。
 *
 * <p>parent_id = 0 表示根部门（与 sys_menu 的约定一致，不用 NULL）。
 * code 非空时全局唯一（DB 上有部分唯一索引），为空则不参与约束。
 */
@Entity
@Table(name = "sys_dept")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SysDept {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 64)
    private String code;

    @Column(length = 64)
    private String leader;

    @Column(length = 32)
    private String phone;

    @Column(nullable = false)
    private Integer sort;

    /** 0=停用 / 1=启用 */
    @Column(nullable = false)
    private Short status;

    @Column(length = 512)
    private String remark;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime n = LocalDateTime.now();
        this.createdAt = n;
        this.updatedAt = n;
        if (this.parentId == null) this.parentId = 0L;
        if (this.sort == null) this.sort = 0;
        if (this.status == null) this.status = 1;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
