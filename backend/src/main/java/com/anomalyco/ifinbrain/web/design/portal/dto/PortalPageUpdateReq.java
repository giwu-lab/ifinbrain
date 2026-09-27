package com.anomalyco.ifinbrain.web.design.portal.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class PortalPageUpdateReq {

    @Size(max = 128)
    private String name;

    @Size(max = 512)
    private String description;

    /** 全量替换 layout_schema（不合并；前端每次 PUT 全量提交） */
    private String layoutSchema;

    /** 全量替换角色绑定；null = 不动；[] = 清空 */
    private List<String> roleBindings;

    private Short status;
}