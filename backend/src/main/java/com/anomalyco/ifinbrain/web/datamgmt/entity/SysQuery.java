package com.anomalyco.ifinbrain.web.datamgmt.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "sys_query")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SysQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 显示名，例如"按发票号查明细"；同一数据源内唯一 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 业务说明，给协作者看 */
    @Column(length = 512)
    private String description;

    /** 关联 sys_datasource.id；外键 CASCADE 由迁移脚本显式加 */
    @Column(name = "datasource_id", nullable = false)
    private Long datasourceId;

    /**
     * SQL 模板：SELECT-only，使用 :param_name 占位参数。
     * 落地前会跑 SqlSafetyChecker（禁 UPDATE/INSERT/DELETE/DROP/ALTER/TRUNCATE/GRANT/REVOKE 等）。
     */
    @Lob
    @Column(name = "sql_template", nullable = false, columnDefinition = "TEXT")
    private String sqlTemplate;

    /**
     * 参数 schema（JSON 数组）：[{name,label,type,required,defaultValue,placeholder}]
     * type: string | number | date | datetime
     */
    @Lob
    @Column(name = "params_schema_json", nullable = false, columnDefinition = "TEXT")
    private String paramsSchemaJson;

    /** 0=禁用 / 1=启用 */
    @Column(nullable = false)
    private Short status;

    /** 最近一次运行：true / false / null=从未 */
    @Column(name = "last_run_ok")
    private Short lastRunOk;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "last_run_row_count")
    private Integer lastRunRowCount;

    @Column(name = "last_run_duration_ms")
    private Long lastRunDurationMs;

    @Column(name = "last_run_msg", length = 255)
    private String lastRunMsg;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

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