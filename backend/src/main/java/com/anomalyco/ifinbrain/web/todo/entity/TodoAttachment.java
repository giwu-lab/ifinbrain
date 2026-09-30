package com.anomalyco.ifinbrain.web.todo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "sys_todo_attachment")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TodoAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "storage_key", nullable = false, length = 512, unique = true)
    private String storageKey;

    /** 存储后端快照：'local' | 's3'。即便系统存储切换，旧附件仍能正确寻址。 */
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

    @Column(name = "uploader_name", length = 64)
    private String uploaderName;

    /** 0 = 已删除；1 = 正常。 */
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