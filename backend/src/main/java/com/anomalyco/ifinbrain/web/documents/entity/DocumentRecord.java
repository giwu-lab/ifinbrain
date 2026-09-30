package com.anomalyco.ifinbrain.web.documents.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "sys_document")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    /** 显示名（可重命名）。 */
    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    /** 存储后端快照：'local' | 's3'。即便系统存储切换，旧档案仍能正确寻址。 */
    @Column(name = "storage_type", nullable = false, length = 16)
    private String storageType;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(length = 64)
    private String sha256;

    @Column(name = "mime_type", length = 128)
    private String mimeType;

    @Column(name = "uploader_id")
    private Long uploaderId;

    /** 冗余字段，避免每行 join sys_user。 */
    @Column(name = "uploader_name", length = 64)
    private String uploaderName;

    @Column(length = 255)
    private String remark;

    /**
     * 文档生命周期状态。两阶段 + 补偿一致性协议（V35）：
     * <ul>
     *   <li>1 = READY（旧称 ACTIVE，正常显示 / 预览 / 下载）</li>
     *   <li>2 = PENDING_UPLOAD（upload 阶段 1：DB 行已建，storage.put 未完成）</li>
     *   <li>3 = PENDING_DELETE（delete 阶段 1：DB 标待删，storage.delete 未完成）</li>
     *   <li>4 = MISSING（DB 有但 storage 找不到；reconcile 标记；列表可见但 FileController 拒绝）</li>
     * </ul>
     * 部分唯一索引 {@code idx_doc_storage_key_active} 只约束 status != 4，
     * 让 PENDING_UPLOAD 与 READY 之间也能维持 unique（防并发上传同名覆盖）。
     */
    @Column(nullable = false)
    private Short status;

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
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
