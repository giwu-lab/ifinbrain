package com.anomalyco.ifinbrain.web.datamgmt.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据导入日志（审计）。
 *
 * <p>datasourceName / operatorName 是<b>快照</b>而非外键：数据源或用户被改名/删除后，
 * 历史记录仍要能读出「当时是谁导到哪张表」。用外键会连带把审计痕迹一起抹掉。
 */
@Entity
@Table(name = "sys_import_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SysImportLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "datasource_id", nullable = false)
    private Long datasourceId;

    @Column(name = "datasource_name", nullable = false, length = 64)
    private String datasourceName;

    @Column(name = "db_type", nullable = false, length = 16)
    private String dbType;

    @Column(name = "target_schema", length = 128)
    private String targetSchema;

    @Column(name = "target_table", nullable = false, length = 128)
    private String targetTable;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "sheet_name", length = 128)
    private String sheetName;

    /** v1 恒为 append；truncate / upsert 属后续阶段 */
    @Column(name = "import_mode", nullable = false, length = 16)
    private String importMode;

    @Column(name = "total_rows", nullable = false)
    private Integer totalRows;

    @Column(name = "inserted_rows", nullable = false)
    private Integer insertedRows;

    @Column(name = "failed_rows", nullable = false)
    private Integer failedRows;

    @Column(name = "skipped_rows", nullable = false)
    private Integer skippedRows;

    /** 0=成功 1=部分成功 2=失败（已回滚） */
    @Column(nullable = false)
    private Short status;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** 前 N 条失败明细的 JSON 数组（完整导出属 Phase 2） */
    @Column(name = "error_detail", columnDefinition = "TEXT")
    private String errorDetail;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "operator_name", length = 64)
    private String operatorName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        if (totalRows == null) totalRows = 0;
        if (insertedRows == null) insertedRows = 0;
        if (failedRows == null) failedRows = 0;
        if (skippedRows == null) skippedRows = 0;
        if (status == null) status = 0;
    }

    @PreUpdate
    void onUpdate() {
        // 导入日志是只追加的审计记录，不提供内容更新路径
    }
}