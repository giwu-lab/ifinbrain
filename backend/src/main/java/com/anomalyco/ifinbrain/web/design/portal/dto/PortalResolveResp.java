package com.anomalyco.ifinbrain.web.design.portal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PortalResolveResp {

    /** 是否命中；false 时 layoutSchema 为 null */
    private boolean hit;

    private Long id;
    private String code;
    private String name;

    /** 命中的 layout JSON（root 子树，给 <DynamicRenderer>） */
    private String layoutSchema;
}