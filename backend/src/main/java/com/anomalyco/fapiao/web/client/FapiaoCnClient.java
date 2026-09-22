package com.anomalyco.fapiao.web.client;

import com.anomalyco.fapiao.web.entity.SysSetting;
import com.anomalyco.fapiao.web.repository.SysSettingRepository;
import com.anomalyco.fapiao.web.security.CryptoUtil;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FapiaoCnClient {

    private final SysSettingRepository settingRepo;
    private final CryptoUtil crypto;
    private volatile WebClient client;
    private volatile CachedConfig cached;

    /** 当前生效配置缓存：优先 sys_setting，未启用则回退 application.yml/env */
    private CachedConfig currentConfig() {
        CachedConfig c = cached;
        if (c != null) return c;
        synchronized (this) {
            if (cached != null) return cached;
            cached = loadFromSettings();
            return cached;
        }
    }

    private CachedConfig loadFromSettings() {
        try {
            String enabled = settingRepo.findById("parser.enabled")
                    .map(SysSetting::getValueText).orElse("false");
            if (!"true".equalsIgnoreCase(enabled)) {
                throw new com.anomalyco.fapiao.web.common.BizException(
                        503, "PARSER_NOT_CONFIGURED: 未启用自定义解析 API，请到 系统 → API 设置 中配置");
            }
            String url = settingRepo.findById("parser.baseUrl")
                    .map(SysSetting::getValueText).orElse("");
            String encKey = settingRepo.findById("parser.apiKey")
                    .map(SysSetting::getValueText).orElse("");
            String to = settingRepo.findById("parser.timeoutSeconds")
                    .map(SysSetting::getValueText).orElse("60");
            int timeout = 60;
            try { timeout = Integer.parseInt(to); } catch (Exception ignore) {}
            String extractPath = readSetting("parser.extractPath", "/extract");
            String batchPath = readSetting("parser.batchPath", "/extract/batch");
            String healthPath = readSetting("parser.healthPath", "/health");
            if (url.isBlank()) {
                throw new com.anomalyco.fapiao.web.common.BizException(
                        503, "PARSER_NOT_CONFIGURED: 解析 API Base URL 未配置，请到 系统 → API 设置 中填写");
            }
            return new CachedConfig(url, crypto.decrypt(encKey), timeout, extractPath, batchPath, healthPath, true);
        } catch (com.anomalyco.fapiao.web.common.BizException ex) {
            throw ex;
        } catch (Exception ex) {
            // db 还没准备好 / 任何 IO 异常 → 视为未配置
            throw new com.anomalyco.fapiao.web.common.BizException(
                    503, "PARSER_NOT_CONFIGURED: 解析 API 配置读取失败，请到 系统 → API 设置 中检查");
        }
    }

    private String readSetting(String key, String def) {
        String v = settingRepo.findById(key).map(SysSetting::getValueText).orElse(def);
        if (v == null || v.isBlank()) return def;
        return v;
    }

    /** 由 SettingService.saveOcrConfig() 调用，使新配置立即生效。异常被吞——reload 只是预热缓存，失败仅意味着「OCR 暂未就绪」 */
    public synchronized void reload() {
        try {
            CachedConfig fresh = loadFromSettings();
            this.cached = fresh;
            this.client = null;
            log.info("FapiaoCn client reloaded: baseUrl={}, extractPath={}, batchPath={}, healthPath={}",
                fresh.baseUrl, fresh.extractPath, fresh.batchPath, fresh.healthPath);
        } catch (com.anomalyco.fapiao.web.common.BizException ex) {
            // 保存配置时若尚未启用/未配置，不阻断设置保存——只是把缓存置空，识别时会再被 BizException 拒绝。
            log.info("FapiaoCn client reload skipped: {}", ex.getMessage());
            this.cached = null;
            this.client = null;
        } catch (Exception ex) {
            log.warn("FapiaoCn client reload failed: {}", ex.getMessage());
            this.cached = null;
            this.client = null;
        }
    }

    private WebClient client() {
        WebClient c = client;
        if (c != null) return c;
        synchronized (this) {
            if (client != null) return client;
            CachedConfig cfg = currentConfig();
            client = WebClient.builder()
                    .baseUrl(cfg.baseUrl)
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.MULTIPART_FORM_DATA_VALUE)
                    .build();
            return client;
        }
    }

    private String apiKey() { return currentConfig().apiKey; }
    private int timeoutSeconds() { return currentConfig().timeoutSeconds; }
    private String batchPath() { return currentConfig().batchPath; }
    private String extractPath() { return currentConfig().extractPath; }

    /**
     * 健康检查：直接用 WebClient， 与业务调用走同样的「绝对路径替换 baseUrl.path」机制，
     * 避免字符串拼接的反直觉问题：填 http://x:8091/extract 测试连通会被解释成 GET http://x:8091/health。
     * 返回的 Map 含 ok / body / url， url 是实际请求的完整地址（前端可展示）。
     */
    public java.util.Map<String, Object> probeHealth(String overrideUrl) {
        String base;
        String path;
        try {
            CachedConfig c = currentConfig();
            base = c.baseUrl;
            path = c.healthPath;
        } catch (Exception e) {
            // 配置未启用 / 未配置：用 overrideUrl 兜底，healthPath 默认 /health
            base = overrideUrl;
            path = "/health";
        }
        if (base == null || base.isBlank()) {
            throw new com.anomalyco.fapiao.web.common.BizException(400, "baseUrl is required");
        }
        if (path == null || path.isBlank()) path = "/health";
        if (!path.startsWith("/")) path = "/" + path;
        try {
            WebClient probe = WebClient.builder().baseUrl(base).build();
            java.util.Map<?, ?> body = probe.get()
                    .uri(path)
                    .retrieve()
                    .bodyToMono(java.util.Map.class)
                    .timeout(Duration.ofSeconds(5))
                    .onErrorResume(e -> Mono.just(java.util.Map.of("error", String.valueOf(e.getMessage()))))
                    .block();
            boolean ok = body != null && ("ok".equals(String.valueOf(body.get("status")))
                    || "UP".equals(String.valueOf(body.get("status"))));
            return java.util.Map.of("ok", ok, "body", body == null ? "" : body, "url", base + path);
        } catch (Exception e) {
            throw new com.anomalyco.fapiao.web.common.BizException(502, "连通失败: " + e.getMessage());
        }
    }

    public List<ExtractItem> extractBatch(List<FilePart> files) {
        if (files == null || files.isEmpty()) return List.of();
        MultipartBodyBuilder mb = new MultipartBodyBuilder();
        for (FilePart f : files) {
            mb.part("files", new ByteArrayResource(f.bytes) {
                @Override public String getFilename() { return f.filename; }
            });
        }
        String key = apiKey();
        String path = batchPath();
        List<ExtractItem> result = client().post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, key.isBlank() ? "" : "Bearer " + key)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(mb.build()))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<List<ExtractItem>>() {})
                .timeout(Duration.ofSeconds(timeoutSeconds()))
                .onErrorResume(e -> {
                    log.error("FapiaoCn {} failed", path, e);
                    return Mono.just(files.stream().map(f -> ExtractItem.error(f.filename, e.getMessage())).toList());
                })
                .block();
        return result != null ? result : List.of();
    }

    public ExtractItem extractSingle(FilePart f) {
        MultipartBodyBuilder mb = new MultipartBodyBuilder();
        mb.part("file", new ByteArrayResource(f.bytes) {
            @Override public String getFilename() { return f.filename; }
        });
        String key = apiKey();
        String path = extractPath();
        ExtractItem resp = client().post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, key.isBlank() ? "" : "Bearer " + key)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(mb.build()))
                .retrieve()
                .bodyToMono(ExtractItem.class)
                .timeout(Duration.ofSeconds(timeoutSeconds()))
                .onErrorResume(e -> {
                    log.error("FapiaoCn {} failed", path, e);
                    return Mono.just(ExtractItem.error(f.filename, e.getMessage()));
                })
                .block();
        return resp != null ? resp : ExtractItem.error(f.filename, "empty response");
    }

    /** 内部缓存：baseUrl/apiKey/timeoutSeconds/extractPath/batchPath/healthPath/是否启用自定义。 */
    private static final class CachedConfig {
        final String baseUrl;
        final String apiKey;
        final int timeoutSeconds;
        final String extractPath;
        final String batchPath;
        final String healthPath;
        final boolean customEnabled;
        CachedConfig(String baseUrl, String apiKey, int timeoutSeconds,
                     String extractPath, String batchPath, String healthPath, boolean customEnabled) {
            this.baseUrl = baseUrl; this.apiKey = apiKey;
            this.timeoutSeconds = timeoutSeconds; this.customEnabled = customEnabled;
            this.extractPath = extractPath; this.batchPath = batchPath; this.healthPath = healthPath;
        }
    }

    @Data
    public static class FilePart {
        public String filename;
        public byte[] bytes;
        public FilePart(String filename, byte[] bytes) { this.filename = filename; this.bytes = bytes; }
    }

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class ExtractItem {
        public String file;
        public Integer pageCount;
        public String invoiceType;
        public String invoiceNumber;
        public LocalDate issueDate;
        public String buyerName;
        public String buyerTaxId;
        public String sellerName;
        public String sellerTaxId;
        public BigDecimal amountWithoutTax;
        public BigDecimal taxAmount;
        public BigDecimal totalAmount;
        public String totalAmountUpper;
        public String payee;
        public String reviewer;
        public String drawer;
        public String message;

        public static ExtractItem error(String fn, String msg) {
            ExtractItem i = new ExtractItem();
            i.file = fn;
            i.message = "error: " + msg;
            return i;
        }
    }
}