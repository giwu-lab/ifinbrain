package com.anomalyco.ifinbrain.web.preference.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.preference.dto.UserPreferenceDto;
import com.anomalyco.ifinbrain.web.preference.entity.SysUserPreference;
import com.anomalyco.ifinbrain.web.preference.repository.SysUserPreferenceRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Slf4j
public class PreferenceService {

    private final SysUserPreferenceRepository repo;
    private final PermissionChecker perm;
    private final ObjectMapper mapper = new ObjectMapper();

    @Transactional(readOnly = true)
    public UserPreferenceDto get() {
        Long uid = perm.currentUserId();
        if (uid == null) throw new BizException(401, "Not authenticated");
        return repo.findById(uid).map(UserPreferenceDto::fromEntity)
                .orElseGet(() -> new UserPreferenceDto(uid, null, null));
    }

    /**
     * upsert：当前用户的偏好。空 / null → 抛 400（必须先在前端做合并）。
     * JSON 形状由前端保证；这里只做一次 parse 校验，防止存脏数据。
     */
    @Transactional
    public UserPreferenceDto upsert(String themeJson) {
        Long uid = perm.currentUserId();
        if (uid == null) throw new BizException(401, "Not authenticated");
        if (themeJson == null || themeJson.isBlank()) {
            throw new BizException(400, "themeJson is required");
        }
        try {
            mapper.readTree(themeJson);
        } catch (JsonProcessingException e) {
            throw new BizException(400, "themeJson is not valid JSON: " + e.getOriginalMessage());
        }
        SysUserPreference e = repo.findById(uid)
                .orElseGet(() -> SysUserPreference.builder().userId(uid).build());
        e.setThemeJson(themeJson);
        e = repo.save(e);
        return UserPreferenceDto.fromEntity(e);
    }

    @Transactional
    public void reset() {
        Long uid = perm.currentUserId();
        if (uid == null) return;
        repo.deleteById(uid);
    }
}