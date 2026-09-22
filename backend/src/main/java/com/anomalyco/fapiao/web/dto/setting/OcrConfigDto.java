package com.anomalyco.fapiao.web.dto.setting;

import lombok.Data;

@Data
public class OcrConfigDto {
    /** 是否使用 sys_setting 中的自定义识别服务（默认 false，使用 application.yml/env 配置） */
    private boolean enabled = false;
    private String baseUrl;
    /** 明文 API Key（前端传入时是明文，返回时被脱敏）。保存到 sys_setting 前由 service 层加密。 */
    private String apiKey;
    private Integer timeoutSeconds = 60;
}