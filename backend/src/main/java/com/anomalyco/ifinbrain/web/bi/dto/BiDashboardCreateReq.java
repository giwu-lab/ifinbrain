package com.anomalyco.ifinbrain.web.bi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BiDashboardCreateReq {

    @NotBlank
    @Size(max = 64)
    private String code;

    @NotBlank
    @Size(max = 128)
    private String name;

    @Size(max = 512)
    private String description;

    /** 画布定义；必填 —— 新建大屏必须带一份 layout（哪怕是空画布） */
    @NotNull
    private DashboardLayout layout;

    /** 可空；不传则沿用 layout.theme */
    private DashboardLayout.Theme theme;

    /** 自动刷新秒数；可空，默认 0（不自动刷新） */
    private Integer refreshInterval;
}
