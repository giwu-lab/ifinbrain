package com.anomalyco.fapiao.web.service;

import com.anomalyco.fapiao.web.client.FapiaoCnClient;
import com.anomalyco.fapiao.web.common.BizException;
import com.anomalyco.fapiao.web.dto.setting.OcrConfigDto;
import com.anomalyco.fapiao.web.dto.setting.StorageConfigDto;
import com.anomalyco.fapiao.web.entity.SysSetting;
import com.anomalyco.fapiao.web.repository.SysSettingRepository;
import com.anomalyco.fapiao.web.security.CryptoUtil;
import com.anomalyco.fapiao.web.security.PermissionChecker;
import com.anomalyco.fapiao.web.storage.StorageSelector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

import java.time.Duration;
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
     * 读取识别 API 设置。apiKey 字段**永远返回占位字符串**（前端有值时显示掩码，留空表示不变）。
     */
    @Transactional(readOnly = true)
    public OcrConfigDto getOcrConfig() {
        perm.require("ocr:view");
        OcrConfigDto dto = new OcrConfigDto();
        dto.setEnabled(Boolean.parseBoolean(getString("ocr.enabled", "false")));
        dto.setBaseUrl(getString("ocr.baseUrl", ""));
        String stored = getString("ocr.apiKey", "");
        dto.setApiKey(stored.isEmpty() ? "" : "********");
        try { dto.setTimeoutSeconds(Integer.parseInt(getString("ocr.timeoutSeconds", "60"))); }
        catch (Exception e) { dto.setTimeoutSeconds(60); }
        return dto;
    }

    @Transactional
    public void saveOcrConfig(OcrConfigDto dto) {
        perm.require("ocr:update");
        if (dto == null) throw new BizException(400, "body is required");
        save("ocr.enabled", String.valueOf(dto.isEnabled()));
        if (dto.getBaseUrl() != null) save("ocr.baseUrl", dto.getBaseUrl().trim());
        if (dto.getTimeoutSeconds() != null && dto.getTimeoutSeconds() > 0) {
            save("ocr.timeoutSeconds", String.valueOf(dto.getTimeoutSeconds()));
        }
        // apiKey：空串表示保留旧值；非空才覆盖
        if (dto.getApiKey() != null && !dto.getApiKey().isEmpty()
                && !"********".equals(dto.getApiKey())) {
            save("ocr.apiKey", crypto.encrypt(dto.getApiKey()));
        }
        fapiaoCnClient.reload();
    }

    /**
     * 仅用于前端「测试连通」按钮的轻量探活：直接 GET 外部 {baseUrl}/health，
     * 走 WebClient 不带认证，绕开浏览器 CORS 前置预检。
     * 返回结构：{ok: bool, httpStatus, body}，前端只看 ok。
     */
    public Map<String, Object> testOcrConnection(OcrConfigDto dto) {
        perm.require("ocr:view");
        String url = dto != null && dto.getBaseUrl() != null ? dto.getBaseUrl().trim() : "";
        if (url.isEmpty()) {
            // 没传 baseUrl 就用当前已存的配置
            url = getString("ocr.baseUrl", "");
        }
        if (url.isEmpty()) throw new BizException(400, "baseUrl is required");
        String healthUrl = url.replaceAll("/+$", "") + "/health";
        try {
            WebClient probe = WebClient.builder().baseUrl(healthUrl).build();
            Map<?, ?> body = probe.get()
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(5))
                    .onErrorResume(e -> Mono.just(Map.of("error", String.valueOf(e.getMessage()))))
                    .block();
            boolean ok = body != null && ("ok".equals(String.valueOf(body.get("status")))
                    || "UP".equals(String.valueOf(body.get("status"))));
            return Map.of("ok", ok, "body", body == null ? "" : body);
        } catch (Exception e) {
            throw new BizException(502, "连通失败: " + e.getMessage());
        }
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