package com.anomalyco.ifinbrain.web.report.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 报表。与 {@code SysBiDashboard} 同构思路：结构化定义（查询头 / 列配置）塞 JSON 列，
 * 标量字段留在关系列上。
 *
 * <p>关键差异：报表<b>不含 SQL</b>。SQL 与参数 schema 的唯一真源是 {@code sys_query}
 * （{@code queryId} 指向它）。所以改 SQL 不需要动报表，报表只是「怎么展示这个查询」。
 *
 * <p>查询头同理 —— 字段定义运行时从 query 的 params_schema_json 读，
 * {@code headerJson} 只存「显示哪些、按什么顺序、每行放几个」。
 */
@Entity
@Table(name = "sys_report")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务编码，全局唯一 */
    @Column(nullable = false, length = 64, unique = true)
    private String code;

    /** 显示名 */
    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    /** 指向 sys_query.id；DB 层 ON DELETE RESTRICT，删查询前得先处理报表 */
    @Column(name = "query_id", nullable = false)
    private Long queryId;

    /**
     * 查询头布局 JSON（HeaderLayout）：
     * { "visible":["status","startDate"], "collapsed":false, "columns":4 }
     */
    @Column(name = "header_json", columnDefinition = "TEXT")
    private String headerJson;

    /**
     * 列配置 JSON（ColumnCfg[]）：
     * [ { "key":"amount", "label":"金额", "width":140, "align":"right", "fixed":false, "visible":true } ]
     */
    @Column(name = "columns_json", columnDefinition = "TEXT")
    private String columnsJson;

    /** 查询头默认值 JSON（name → value），打开报表即可出数 */
    @Column(name = "default_params", columnDefinition = "TEXT")
    private String defaultParams;

    /** 虚拟滚动每页行数（V55 CHECK 约束 20..2000） */
    @Column(name = "page_size", nullable = false)
    private Integer pageSize;

    /** 允许加载的总行数护栏；0 = 不限制 */
    @Column(name = "max_rows", nullable = false)
    private Integer maxRows;

    /** 0=草稿 / 1=已发布 */
    @Column(nullable = false)
    private Short status;

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
        if (this.status == null) this.status = 0;
        if (this.pageSize == null) this.pageSize = 50;
        if (this.maxRows == null) this.maxRows = 50000;
        if (this.createdBy == null) this.createdBy = "system";
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
