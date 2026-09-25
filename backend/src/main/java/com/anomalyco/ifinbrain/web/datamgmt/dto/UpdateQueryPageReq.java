package com.anomalyco.ifinbrain.web.datamgmt.dto;

import lombok.Data;

@Data
public class UpdateQueryPageReq {
    /** 新菜单名；null = 不改 */
    private String menuName;
    /** 新菜单图标 (Element Plus 图标组件名)；null = 不改；空字符串 = 设为默认 Document */
    private String icon;
    /** 新菜单排序；null = 不改 */
    private Integer sort;
    /** 新 configJson；null = 不改 */
    private String configJson;
}