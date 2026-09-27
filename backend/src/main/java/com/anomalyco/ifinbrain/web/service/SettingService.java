package com.anomalyco.ifinbrain.web.service;

import com.anomalyco.ifinbrain.web.client.FapiaoCnClient;
import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.dto.setting.ParserConfigDto;
import com.anomalyco.ifinbrain.web.dto.setting.StorageConfigDto;
import com.anomalyco.ifinbrain.web.entity.SysSetting;
import com.anomalyco.ifinbrain.web.repository.SysSettingRepository;
import com.anomalyco.ifinbrain.web.security.CryptoUtil;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.anomalyco.ifinbrain.web.storage.S3StorageService;
import com.anomalyco.ifinbrain.web.storage.StorageSelector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class SettingService {

    private final SysSettingRepository settingRepo;
    private final PermissionChecker perm;
    private final StorageSelector storageSelector;
    private final S3StorageService s3Storage;
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
        // 密钥字段：与解析页 API Key 同样三态 —— 探测解密能成功才回掩码 ok；
        // 解密失败（如主密钥轮换）→ 不回掩码，避免误导为「已配置」，前端按 failed 态引导重新填写。
        dto.getS3().setAccessKey(probeSecret("storage.s3.accessKey", dto.getS3()::setAccessKeyStatus));
        dto.getS3().setSecretKey(probeSecret("storage.s3.secretKey", dto.getS3()::setSecretKeyStatus));
        dto.getS3().setPathStyle(Boolean.parseBoolean(getString("storage.s3.pathStyle", "true")));
        return dto;
    }

    /**
     * 探测密钥密文：未存 → "" + missing；可解密 → "********" + ok；不可解密 → "" + failed。
     * 读操作不应阻断 UI，捕获所有异常并降级为 failed。
     */
    private String probeSecret(String settingKey, java.util.function.Consumer<String> statusSetter) {
        String stored = getString(settingKey, "");
        if (stored.isEmpty()) {
            statusSetter.accept("missing");
            return "";
        }
        try {
            crypto.decrypt(stored);
            statusSetter.accept("ok");
            return "********";
        } catch (Exception ex) {
            log.warn("Storage secret {} cannot be decrypted with current master key (likely rotated): {}",
                    settingKey, ex.getMessage());
            statusSetter.accept("failed");
            return "";
        }
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
     * 测试 S3 连通：用前端传入的覆盖配置（未存字段回落到已存 / yaml）。
     * 仅在 type=s3 时生效；返回 {ok, message, latencyMs, bucket}。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> testStorageConnection(StorageConfigDto dto) {
        perm.require("setting:view");
        if (dto == null || dto.getS3() == null) {
            throw new BizException(400, "s3 config is required");
        }
        S3StorageService.EffectiveCfg current = s3Storage.currentEffective();
        StorageConfigDto.S3 in = dto.getS3();
        String endpoint  = emptyToDefault(in.getEndpoint(),  current.endpoint());
        String region    = emptyToDefault(in.getRegion(),    current.region());
        String bucket    = emptyToDefault(in.getBucket(),    current.bucket());
        boolean pathStyle = in.isPathStyle(); // 默认 true；前端显式 false 时生效
        // 密钥：用户可空 / 掩码 / 新值；掩码视为保留旧值
        String accessKey = resolveSecret(in.getAccessKey(), current.accessKey());
        String secretKey = resolveSecret(in.getSecretKey(), current.secretKey());
        if (endpoint.isBlank() || bucket.isBlank() || accessKey.isBlank() || secretKey.isBlank()) {
            throw new BizException(400, "endpoint / bucket / accessKey / secretKey 必填");
        }
        return s3Storage.probe(new S3StorageService.EffectiveCfg(
                endpoint, region, bucket, accessKey, secretKey, pathStyle));
    }

    private static String emptyToDefault(String v, String def) {
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private String resolveSecret(String incoming, String stored) {
        if (incoming == null || incoming.isEmpty() || "********".equals(incoming)) return stored;
        return incoming;
    }

    /**
     * 读取解析 API 设置。apiKey 字段**永远返回占位字符串**（前端有值时显示掩码，留空表示不变）。
     * 同时探测解密能否成功，决定 apiKeyStatus：missing / ok / failed。
     */
    @Transactional(readOnly = true)
    public ParserConfigDto getParserConfig() {
        perm.require("parser:view");
        ParserConfigDto dto = new ParserConfigDto();
        dto.setEnabled(Boolean.parseBoolean(getString("parser.enabled", "false")));
        dto.setBaseUrl(getString("parser.baseUrl", ""));
        String stored = getString("parser.apiKey", "");
        if (stored.isEmpty()) {
            dto.setApiKey("");
            dto.setApiKeyStatus("missing");
        } else {
            // 试探解密 — 失败时只把状态置为 failed，**不抛错**（读操作不应阻断 UI）
            try {
                crypto.decrypt(stored);
                dto.setApiKey("********");
                dto.setApiKeyStatus("ok");
            } catch (Exception ex) {
                log.warn("Parser API key cannot be decrypted with current master key (likely rotated): {}", ex.getMessage());
                dto.setApiKey("");          // 不回掩码 — 掩码会误导为「已配置」
                dto.setApiKeyStatus("failed");
            }
        }
        try { dto.setTimeoutSeconds(Integer.parseInt(getString("parser.timeoutSeconds", "60"))); }
        catch (Exception e) { dto.setTimeoutSeconds(60); }
        dto.setExtractPath(getString("parser.extractPath", "/extract"));
        dto.setBatchPath(getString("parser.batchPath", "/extract/batch"));
        dto.setHealthPath(getString("parser.healthPath", "/health"));
        return dto;
    }

    @Transactional
    public void saveParserConfig(ParserConfigDto dto) {
        perm.require("parser:update");
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
        perm.require("parser:view");
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