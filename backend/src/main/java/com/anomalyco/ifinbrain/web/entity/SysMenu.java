package com.anomalyco.ifinbrain.web.entity;

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

    /** 非 NULL = 通过 saved query 一键发布的列表页。单一组件 data-mgmt/query-page/Index。 */
    @Column(name = "query_id")
    private Long queryId;

    /**
     * query 页配置 JSON（nullable）：
     *   { paramLabels: {name: 显示名}, columns: {sourceCol: {headerText, width}} }
     * query_id 非 NULL 时才有意义。
     */
    @Lob
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}