package com.anomalyco.fapiao.web.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RefreshReq {
    @NotBlank
    private String refreshToken;
}