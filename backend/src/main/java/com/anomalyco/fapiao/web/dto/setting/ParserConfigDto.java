package com.anomalyco.fapiao.web.dto.setting;

import lombok.Data;

@Data
public class ParserConfigDto {
    /** 是否启用自定义解析 API（默认 false）。 */
    private boolean enabled = false;

    /** 解析服务 base URL，例如 http://192.168.3.160:8091。 */
    private String baseUrl;

    /** 明文 API Key（前端传入时是明文，返回时被脱敏）。保存到 sys_setting 前由 service 层加密。 */
    private String apiKey;

    /** 请求超时秒数。 */
    private Integer timeoutSeconds = 60;

    /** 单文件解析路径（默认 /extract）。可对接第三方 API 时改为自定义路径。 */
    private String extractPath = "/extract";

    /** 批量解析路径（默认 /extract/batch）。 */
    private String batchPath = "/extract/batch";

    /** 健康检查路径（默认 /health）。 */
    private String healthPath = "/health";
}