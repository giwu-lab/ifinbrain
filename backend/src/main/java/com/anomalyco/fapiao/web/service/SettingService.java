package com.anomalyco.fapiao.web.service;

import com.anomalyco.fapiao.web.client.FapiaoCnClient;
import com.anomalyco.fapiao.web.common.BizException;
import com.anomalyco.fapiao.web.dto.setting.ParserConfigDto;
import com.anomalyco.fapiao.web.dto.setting.StorageConfigDto;
import com.anomalyco.fapiao.web.entity.SysSetting;
import com.anomalyco.fapiao.web.repository.SysSettingRepository;
import com.anomalyco.fapiao.web.security.CryptoUtil;
import com.anomalyco.fapiao.web.security.PermissionChecker;
import com.anomalyco.fapiao.web.storage.StorageSelector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SettingService {

    private final SysSettingRepository settingRepo;
    private final PermissionChecker perm;
    private final StorageSelector storageSelector;
    private final FapiaoCnClient fapiaoCnClient;
    private final CryptoUtil crypto;

    @Transactional(readOnly = true)
    public StorageConfigDto getStorageConfig() {
        perm.require("setting:view");
        StorageConfigDto dto = new StorageConfigDto();
        dto.setType(getString("storage.type", "local"));
        dto.getLocal().setRoot(getString("storage.local.root", "./data/storage"));
        dto.getS3().setEndpoint(getString("storage.s3.endpoint", ""));
        dto.getS3().setRegion(getString("storage.s3.region", "us-east-1"));
        dto.getS3().setBucket(getString("storage.s3.bucket", ""));
        // 密钥字段只回显掩码；前端留空表示「保留旧值」，非空才会被 save 覆盖
        dto.getS3().setAccessKey(getString("storage.s3.accessKey", "").isEmpty() ? "" : "********");
        dto.getS3().setSecretKey(getString("storage.s3.secretKey", "").isEmpty() ? "" : "********");
        dto.getS3().setPathStyle(Boolean.parseBoolean(getString("storage.s3.pathStyle", "true")));
        return dto;
    }

    @Transactional
    public void saveStorageConfig(StorageConfigDto dto) {
        perm.require("setting:update");
        if (dto == null || dto.getType() == null) {
            throw new BizException(400, "type is required");
        }
        if (!dto.getType().equals("local") && !dto.getType().equals("s3")) {
            throw new BizException(400, "type must be local or s3");
        }
        save("storage.type", dto.getType());
        if (dto.getLocal() != null) save("storage.local.root", dto.getLocal().getRoot());
        if (dto.getS3() != null) {
            save("storage.s3.endpoint", dto.getS3().getEndpoint());
            save("storage.s3.region", dto.getS3().getRegion());
            save("storage.s3.bucket", dto.getS3().getBucket());
            // 密钥字段：空 / 掩码 都视为「保留旧值」；否则加密后落库
            saveSecretIfPresent("storage.s3.accessKey", dto.getS3().getAccessKey());
            saveSecretIfPresent("storage.s3.secretKey", dto.getS3().getSecretKey());
            save("storage.s3.pathStyle", String.valueOf(dto.getS3().isPathStyle()));
        }
        storageSelector.reload();
    }

    /** 密钥字段写入：空串 / "********" 掩码都视为不变，否则 AES-256-GCM 加密后存 sys_setting */
    private void saveSecretIfPresent(String key, String plain) {
        if (plain == null || plain.isEmpty() || "********".equals(plain)) return;
        save(key, crypto.encrypt(plain));
    }

    /**
     * 读取解析 API 设置。apiKey 字段**永远返回占位字符串**（前端有值时显示掩码，留空表示不变）。
     */
    @Transactional(readOnly = true)
    public ParserConfigDto getParserConfig() {
        perm.require("ocr:view");
        ParserConfigDto dto = new ParserConfigDto();
        dto.setEnabled(Boolean.parseBoolean(getString("parser.enabled", "false")));
        dto.setBaseUrl(getString("parser.baseUrl", ""));
        String stored = getString("parser.apiKey", "");
        dto.setApiKey(stored.isEmpty() ? "" : "********");
        try { dto.setTimeoutSeconds(Integer.parseInt(getString("parser.timeoutSeconds", "60"))); }
        catch (Exception e) { dto.setTimeoutSeconds(60); }
        dto.setExtractPath(getString("parser.extractPath", "/extract"));
        dto.setBatchPath(getString("parser.batchPath", "/extract/batch"));
        dto.setHealthPath(getString("parser.healthPath", "/health"));
        return dto;
    }

    @Transactional
    public void saveParserConfig(ParserConfigDto dto) {
        perm.require("ocr:update");
        if (dto == null) throw new BizException(400, "body is required");
        save("parser.enabled", String.valueOf(dto.isEnabled()));
        if (dto.getBaseUrl() != null) save("parser.baseUrl", dto.getBaseUrl().trim());
        if (dto.getTimeoutSeconds() != null && dto.getTimeoutSeconds() > 0) {
            save("parser.timeoutSeconds", String.valueOf(dto.getTimeoutSeconds()));
        }
        if (dto.getExtractPath() != null && !dto.getExtractPath().isBlank()) {
            save("parser.extractPath", dto.getExtractPath().trim());
        }
        if (dto.getBatchPath() != null && !dto.getBatchPath().isBlank()) {
            save("parser.batchPath", dto.getBatchPath().trim());
        }
        if (dto.getHealthPath() != null && !dto.getHealthPath().isBlank()) {
            save("parser.healthPath", dto.getHealthPath().trim());
        }
        // apiKey：空串表示保留旧值；非空才覆盖
        if (dto.getApiKey() != null && !dto.getApiKey().isEmpty()
                && !"********".equals(dto.getApiKey())) {
            save("parser.apiKey", crypto.encrypt(dto.getApiKey()));
        }
        fapiaoCnClient.reload();
    }

    /**
     * 测试连通：调用 FapiaoCnClient.probeHealth（内部用 parser.healthPath 配置）。
     * 支持 override：前端可传任意 baseUrl 临时测试；不传则用已存配置。
     * 返回结构：{ok: bool, body, url}，前端 ok / 和 url 两个字段都展示。
     */
    public Map<String, Object> testParserConnection(ParserConfigDto dto) {
        perm.require("ocr:view");
        String overrideUrl = dto != null && dto.getBaseUrl() != null ? dto.getBaseUrl().trim() : "";
        if (overrideUrl.isEmpty()) {
            overrideUrl = getString("parser.baseUrl", "");
        }
        if (overrideUrl.isEmpty()) throw new BizException(400, "baseUrl is required");
        return fapiaoCnClient.probeHealth(overrideUrl);
    }

    private String getString(String key, String def) {
        return settingRepo.findById(key).map(SysSetting::getValueText).orElse(def);
    }

    private void save(String key, String value) {
        SysSetting s = settingRepo.findById(key).orElseGet(() -> {
            SysSetting n = new SysSetting();
            n.setKeyName(key);
            return n;
        });
        s.setValueText(value);
        s.setUpdatedAt(LocalDateTime.now());
        settingRepo.save(s);
    }
}