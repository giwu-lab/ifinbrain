package com.anomalyco.ifinbrain.web.bi.dto;

import com.anomalyco.ifinbrain.web.bi.entity.BiDashboardEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * BI 大屏响应。{@code layout} / {@code theme} 返回**已解析的对象**（不是裸 JSON 串），
 * 前端拿到即可直接喂给设计器 / 播放页。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BiDashboardDto {

    private Long id;
    private String code;
    private String name;
    private String description;

    /** 画布定义（解析自 layout_json） */
    private DashboardLayout layout;

    /** 大屏级主题（解析自 theme_json）；为空表示跟随 layout.theme */
    private DashboardLayout.Theme theme;

    /** 自动刷新秒数；0 = 不自动刷新 */
    private Integer refreshInterval;

    /** 0=草稿 / 1=已发布 */
    private Short status;

    private LocalDateTime publishedAt;

    /** 轮播排序 */
    private Integer sort;

    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static BiDashboardDto from(BiDashboardEntity e,
                                      DashboardLayout layout,
                                      DashboardLayout.Theme theme) {
        return BiDashboardDto.builder()
                .id(e.getId())
                .code(e.getCode())
                .name(e.getName())
                .description(e.getDescription())
                .layout(layout)
                .theme(theme)
                .refreshInterval(e.getRefreshInterval())
                .status(e.getStatus())
                .publishedAt(e.getPublishedAt())
                .sort(e.getSort())
                .createdBy(e.getCreatedBy())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
