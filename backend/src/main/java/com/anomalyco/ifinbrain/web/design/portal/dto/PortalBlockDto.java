package com.anomalyco.ifinbrain.web.design.portal.dto;

import com.anomalyco.ifinbrain.web.design.portal.entity.SysPortalBlock;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PortalBlockDto {

    private Long id;
    private String type;
    private String name;
    private String category;
    private String icon;
    private Integer sort;
    private Short enabled;
    private Short builtIn;

    /** JSON 字符串 */
    private String defaultConfig;

    /** JSON 字符串：{fields:[{key,label,type,default,options?},...]} */
    private String configSchema;

    public static PortalBlockDto from(SysPortalBlock e) {
        return PortalBlockDto.builder()
                .id(e.getId())
                .type(e.getType())
                .name(e.getName())
                .category(e.getCategory())
                .icon(e.getIcon())
                .sort(e.getSort())
                .enabled(e.getEnabled())
                .builtIn(e.getBuiltIn())
                .defaultConfig(e.getDefaultConfig())
                .configSchema(e.getConfigSchema())
                .build();
    }
}