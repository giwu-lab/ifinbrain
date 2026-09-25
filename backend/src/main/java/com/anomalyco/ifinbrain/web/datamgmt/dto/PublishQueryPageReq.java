package com.anomalyco.ifinbrain.web.datamgmt.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PublishQueryPageReq {
    @NotNull
    private Long queryId;
    /** 菜单显示名；缺省 = query.name */
    private String menuName;
    /** 菜单图标 (Element Plus 图标组件名)；null/空时 fallback 'Document' */
    private String icon;
    /** 菜单排序；null 时默认 99（追加到数据管理下最后） */
    private Integer sort;
    /** 配置 JSON：{paramLabels: {name: 显示名}, columns: {sourceCol: {headerText, width}}} */
    private String configJson;
}