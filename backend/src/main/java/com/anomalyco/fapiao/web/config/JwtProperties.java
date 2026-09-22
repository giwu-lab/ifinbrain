package com.anomalyco.fapiao.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "fapiao-web.jwt")
public class JwtProperties {
    private String accessSecret;
    private int accessTtlMinutes = 30;
    private int refreshTtlDays = 7;
}