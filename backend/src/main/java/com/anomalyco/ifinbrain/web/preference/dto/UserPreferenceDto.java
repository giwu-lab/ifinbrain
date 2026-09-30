package com.anomalyco.ifinbrain.web.preference.dto;

import com.anomalyco.ifinbrain.web.preference.entity.SysUserPreference;

import java.time.LocalDateTime;

public record UserPreferenceDto(Long userId, String themeJson, LocalDateTime updatedAt) {
    public static UserPreferenceDto fromEntity(SysUserPreference e) {
        return new UserPreferenceDto(e.getUserId(), e.getThemeJson(), e.getUpdatedAt());
    }
}