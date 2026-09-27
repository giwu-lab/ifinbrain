package com.anomalyco.ifinbrain.web.design.portal.dto;

import com.anomalyco.ifinbrain.web.design.portal.entity.SysPortalPage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PortalPageDto {

    private Long id;
    private String code;
    private String name;
    private String description;

    /** 直接给前端 JSON 字符串（前端 <DynamicRenderer :schema="...">） */
    private String layoutSchema;
    private Integer configVersion;

    /** 0=draft / 1=published */
    private Short status;

    /** 已解析的角色列表 */
    private List<String> roleBindings;

    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime publishedAt;

    public static PortalPageDto from(SysPortalPage e, List<String> roleBindings) {
        return PortalPageDto.builder()
                .id(e.getId())
                .code(e.getCode())
                .name(e.getName())
                .description(e.getDescription())
                .layoutSchema(e.getLayoutSchema())
                .configVersion(e.getConfigVersion())
                .status(e.getStatus())
                .roleBindings(roleBindings)
                .createdBy(e.getCreatedBy())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .publishedAt(e.getPublishedAt())
                .build();
    }
}