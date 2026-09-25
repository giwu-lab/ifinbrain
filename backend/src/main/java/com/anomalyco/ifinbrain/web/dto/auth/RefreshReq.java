package com.anomalyco.ifinbrain.web.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RefreshReq {
    @NotBlank
    private String refreshToken;
}