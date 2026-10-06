package com.anomalyco.ifinbrain.web.bi.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * BI 大屏。与 {@code SysFormDesign} 同构：结构化定义整体塞 {@code layout_json}（大 JSON），
 * 标量字段（code / name / 轮播排序 / 发布态）留在关系列上。
 *
 * <p>设计取舍：组件与画布强绑定（栅格坐标 / 层级 / 数据绑定都在 layout 里），
 * 拆 {@code sys_bi_widget} 独立表只会带来 N+1 与一致性成本 —— 等做「组件模板库 /
 * 跨大屏复用」时再拆。
 */
@Entity
@Table(name = "sys_bi_dashboard")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BiDashboardEntity {

    /** 空画布：只有 version + widgets，canvas/theme 交给前端按默认值补 */
    static final String EMPTY_LAYOUT_JSON = "{\"version\":1,\"widgets\":[]}";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务编码，全局唯一；复制时派生 {@code xxx_copy} */
    @Column(nullable = false, length = 64, unique = true)
    private String code;

    /** 显示名 */
    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    /**
     * 画布定义 JSON（DashboardLayout）：
     * { "version":1, "canvas":{...}, "theme":{...}, "widgets":[...] }
     */
    @Column(name = "layout_json", nullable = false, columnDefinition = "TEXT")
    private String layoutJson;

    /** 大屏级主题覆盖（DashboardTheme）；播放页可只读它，不必每次 parse layout */
    @Column(name = "theme_json", columnDefinition = "TEXT")
    private String themeJson;

    /** 自动刷新秒数；0 = 不自动刷新 */
    @Column(name = "refresh_interval", nullable = false)
    private Integer refreshInterval;

    /** 0=草稿 / 1=已发布 */
    @Column(nullable = false)
    private Short status;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    /** 轮播排序 */
    @Column(nullable = false)
    private Integer sort;

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
        if (this.refreshInterval == null) this.refreshInterval = 0;
        if (this.sort == null) this.sort = 0;
        // 兜底：正常路径下 BiDashboardService 一定会写入完整 layout（create 的 layout 是 @NotNull），
        // 这里只防「直接 save 一个空实体」把 NOT NULL 列写崩。画布/主题的默认值由前端按 1920x1080 补。
        if (this.layoutJson == null || this.layoutJson.isBlank()) {
            this.layoutJson = EMPTY_LAYOUT_JSON;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
