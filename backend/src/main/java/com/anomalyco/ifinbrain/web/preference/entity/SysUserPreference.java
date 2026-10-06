package com.anomalyco.ifinbrain.web.preference.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * V39: 用户主题偏好（per-user）。
 *
 *  - 一个用户一行；theme_json 存 ThemeSettings 的 JSON 字符串
 *  - 不依赖 DB schema 字段：将来 theme 字段加减都不需迁移
 *  - 默认值由前端 store 提供；首次 PUT 落库后即生效
 *  - JSON 大小限制 2000 字节（≈ 完整 ThemeSettings 序列化 ≈ 600 字节，留 3x 余量）
 */
@Entity
@Table(name = "sys_user_preference")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SysUserPreference {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "theme_json", nullable = false, columnDefinition = "TEXT")
    private String themeJson;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}