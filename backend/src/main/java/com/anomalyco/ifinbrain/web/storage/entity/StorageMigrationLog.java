package com.anomalyco.ifinbrain.web.storage.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 档案库 storage 后端迁移日志（V36）。
 *
 * 设计要点：
 *  - status: 1=ok / 2=fail
 *  - 幂等：service 层用 (storage_key, latest status=1) 跳过已迁
 *  - 进度：service 按时间窗口聚合
 */
@Entity
@Table(name = "storage_migration_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StorageMigrationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_type", nullable = false, length = 16)
    private String fromType;

    @Column(name = "to_type", nullable = false, length = 16)
    private String toType;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(nullable = false)
    private Short status;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(length = 64)
    private String sha256;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @PrePersist
    void onCreate() {
        if (this.startedAt == null) this.startedAt = LocalDateTime.now();
        if (this.status == null) this.status = (short) 2;
    }

    public void markFinished(boolean ok, String errMsg) {
        this.finishedAt = LocalDateTime.now();
        this.status = (short) (ok ? 1 : 2);
        this.errorMsg = errMsg;
    }
}