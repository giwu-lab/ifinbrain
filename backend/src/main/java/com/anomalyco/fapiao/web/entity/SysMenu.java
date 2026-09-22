package com.anomalyco.fapiao.web.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "sys_menu")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SysMenu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 255)
    private String path;

    @Column(length = 255)
    private String component;

    @Column(length = 64)
    private String icon;

    @Column(nullable = false)
    private Integer sort;

    @Column(length = 128)
    private String permission;

    @Column(nullable = false, length = 16)
    private String type; // DIR / MENU / BUTTON

    @Column(nullable = false)
    private Short status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}