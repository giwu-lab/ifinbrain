package com.anomalyco.ifinbrain.web.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginReq {
    @NotBlank
    private String username;
    @NotBlank
    private String password;
}