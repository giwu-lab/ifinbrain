package com.anomalyco.fapiao.web.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "sys_dict_item")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SysDictItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dict_id", nullable = false)
    private Long dictId;

    @Column(name = "parent_id", nullable = false)
    private Long parentId = 0L;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String label;

    @Column(length = 255)
    private String value;

    @Column(nullable = false)
    private Integer sort = 0;

    @Column(nullable = false)
    private Short status = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.parentId == null) this.parentId = 0L;
        if (this.sort == null) this.sort = 0;
        if (this.status == null) this.status = 1;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}