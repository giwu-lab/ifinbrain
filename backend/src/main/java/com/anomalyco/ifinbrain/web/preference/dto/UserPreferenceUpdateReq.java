package com.anomalyco.ifinbrain.web.preference.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserPreferenceUpdateReq(
        @NotBlank @Size(max = 2000, message = "themeJson too long (max 2000)") String themeJson) {
}