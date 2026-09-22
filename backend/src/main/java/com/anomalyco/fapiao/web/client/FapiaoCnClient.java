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
            String enabled = settingRepo.findById("ocr.enabled")
                    .map(SysSetting::getValueText).orElse("false");
            if (!"true".equalsIgnoreCase(enabled)) {
                throw new com.anomalyco.fapiao.web.common.BizException(
                        503, "OCR_NOT_CONFIGURED: 未启用自定义识别 API，请到 系统 → API 设置 中配置");
            }
            String url = settingRepo.findById("ocr.baseUrl")
                    .map(SysSetting::getValueText).orElse("");
            String encKey = settingRepo.findById("ocr.apiKey")
                    .map(SysSetting::getValueText).orElse("");
            String to = settingRepo.findById("ocr.timeoutSeconds")
                    .map(SysSetting::getValueText).orElse("60");
            int timeout = 60;
            try { timeout = Integer.parseInt(to); } catch (Exception ignore) {}
            if (url.isBlank()) {
                throw new com.anomalyco.fapiao.web.common.BizException(
                        503, "OCR_NOT_CONFIGURED: 识别 API Base URL 未配置，请到 系统 → API 设置 中填写");
            }
            return new CachedConfig(url, crypto.decrypt(encKey), timeout, true);
        } catch (com.anomalyco.fapiao.web.common.BizException ex) {
            throw ex;
        } catch (Exception ex) {
            // db 还没准备好 / 任何 IO 异常 → 视为未配置
            throw new com.anomalyco.fapiao.web.common.BizException(
                    503, "OCR_NOT_CONFIGURED: 识别 API 配置读取失败，请到 系统 → API 设置 中检查");
        }
    }

    /** 由 SettingService.saveOcrConfig() 调用，使新配置立即生效。异常被吞——reload 只是预热缓存，失败仅意味着「OCR 暂未就绪」 */
    public synchronized void reload() {
        try {
            CachedConfig fresh = loadFromSettings();
            this.cached = fresh;
            this.client = null;
            log.info("FapiaoCn client reloaded: baseUrl={}, customEnabled={}",
                fresh.baseUrl, fresh.customEnabled);
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

    public List<ExtractItem> extractBatch(List<FilePart> files) {
        if (files == null || files.isEmpty()) return List.of();
        MultipartBodyBuilder mb = new MultipartBodyBuilder();
        for (FilePart f : files) {
            mb.part("files", new ByteArrayResource(f.bytes) {
                @Override public String getFilename() { return f.filename; }
            });
        }
        String key = apiKey();
        List<ExtractItem> result = client().post()
                .uri("/extract/batch")
                .header(HttpHeaders.AUTHORIZATION, key.isBlank() ? "" : "Bearer " + key)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(mb.build()))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<List<ExtractItem>>() {})
                .timeout(Duration.ofSeconds(timeoutSeconds()))
                .onErrorResume(e -> {
                    log.error("FapiaoCn /extract/batch failed", e);
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
        ExtractItem resp = client().post()
                .uri("/extract")
                .header(HttpHeaders.AUTHORIZATION, key.isBlank() ? "" : "Bearer " + key)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(mb.build()))
                .retrieve()
                .bodyToMono(ExtractItem.class)
                .timeout(Duration.ofSeconds(timeoutSeconds()))
                .onErrorResume(e -> {
                    log.error("FapiaoCn /extract failed", e);
                    return Mono.just(ExtractItem.error(f.filename, e.getMessage()));
                })
                .block();
        return resp != null ? resp : ExtractItem.error(f.filename, "empty response");
    }

    /** 内部缓存：baseUrl/apiKey/timeoutSeconds/是否启用自定义。 */
    private static final class CachedConfig {
        final String baseUrl;
        final String apiKey;
        final int timeoutSeconds;
        final boolean customEnabled;
        CachedConfig(String baseUrl, String apiKey, int timeoutSeconds, boolean customEnabled) {
            this.baseUrl = baseUrl; this.apiKey = apiKey;
            this.timeoutSeconds = timeoutSeconds; this.customEnabled = customEnabled;
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