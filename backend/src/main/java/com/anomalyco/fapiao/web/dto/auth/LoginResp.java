package com.anomalyco.fapiao.web.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResp {
    private String accessToken;
    private String refreshToken;
    private long accessTtlSeconds;
    private long refreshTtlSeconds;
    private UserInfo user;
}