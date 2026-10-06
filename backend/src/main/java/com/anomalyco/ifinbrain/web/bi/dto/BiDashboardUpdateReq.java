package com.anomalyco.ifinbrain.web.bi.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新请求：全部字段可选，null = 不改。
 * {@code layout} 走**全量覆盖**语义（前端设计器手里就是完整画布），不做局部 patch。
 */
@Data
public class BiDashboardUpdateReq {

    @Size(max = 128)
    private String name;

    @Size(max = 512)
    private String description;

    private DashboardLayout layout;

    private DashboardLayout.Theme theme;

    /** 自动刷新秒数；0 = 不自动刷新 */
    private Integer refreshInterval;

    /** 轮播排序 */
    private Integer sort;
}
