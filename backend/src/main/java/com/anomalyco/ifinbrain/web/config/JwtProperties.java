package com.anomalyco.ifinbrain.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ifinbrain.jwt")
public class JwtProperties {
    private String accessSecret;
    private int accessTtlMinutes = 30;
    private int refreshTtlDays = 7;
}