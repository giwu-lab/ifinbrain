package com.anomalyco.ifinbrain.web.client;

import com.anomalyco.ifinbrain.web.entity.SysSetting;
import com.anomalyco.ifinbrain.web.repository.SysSettingRepository;
import com.anomalyco.ifinbrain.web.security.CryptoUtil;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class FapiaoCnClient {

    private final SysSettingRepository settingRepo;
    private final CryptoUtil crypto;
    private volatile HttpClient jdkClient;
    private volatile WebClient webClient;
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
                throw new com.anomalyco.ifinbrain.web.common.BizException(
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
                throw new com.anomalyco.ifinbrain.web.common.BizException(
                        503, "PARSER_NOT_CONFIGURED: 解析 API Base URL 未配置，请到 系统 → API 设置 中填写");
            }
            return new CachedConfig(url, crypto.decrypt(encKey), timeout, extractPath, batchPath, healthPath, true);
        } catch (com.anomalyco.ifinbrain.web.common.BizException ex) {
            throw ex;
        } catch (Exception ex) {
            // db 还没准备好 / 任何 IO 异常 → 视为未配置
            throw new com.anomalyco.ifinbrain.web.common.BizException(
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
            this.jdkClient = null;
            this.webClient = null;
            log.info("FapiaoCn client reloaded: baseUrl={}, extractPath={}, batchPath={}, healthPath={}",
                fresh.baseUrl, fresh.extractPath, fresh.batchPath, fresh.healthPath);
        } catch (com.anomalyco.ifinbrain.web.common.BizException ex) {
            // 保存配置时若尚未启用/未配置，不阻断设置保存——只是把缓存置空，识别时会再被 BizException 拒绝。
            log.info("FapiaoCn client reload skipped: {}", ex.getMessage());
            this.cached = null;
            this.jdkClient = null;
            this.webClient = null;
        } catch (Exception ex) {
            log.warn("FapiaoCn client reload failed: {}", ex.getMessage());
            this.cached = null;
            this.jdkClient = null;
            this.webClient = null;
        }
    }

    private HttpClient jdkClient() {
        HttpClient c = jdkClient;
        if (c != null) return c;
        synchronized (this) {
            if (jdkClient != null) return jdkClient;
            jdkClient = HttpClient.newBuilder()
                    // HttpClient 本身不缓存 TCP 连接，需要 server 端 keepalive；这里只用作连接池入口。
                    // 实际超时由 HttpRequest 自身的 timeout() 控制。
                    .version(HttpClient.Version.HTTP_1_1)  // 强制 HTTP/1.1（fapiao_cn uvicorn 默认 HTTP/1.1）
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            return jdkClient;
        }
    }

    private WebClient webClient() {
        WebClient c = webClient;
        if (c != null) return c;
        synchronized (this) {
            if (webClient != null) return webClient;
            CachedConfig cfg = currentConfig();
            webClient = WebClient.builder()
                    .baseUrl(cfg.baseUrl)
                    .build();
            return webClient;
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
        // override 优先：用户可能正在改地址，「测试连通」必须探测表单里填的那个值。
        // 原实现只在「配置缺失 / 未启用」时才用 override；配置一旦存在就把 override 整个丢掉，
        // 于是改完地址点测试，看到的仍是库里旧地址的探测结果 —— 改完看着还是不通，误导排查方向。
        String savedBase = null;
        try {
            savedBase = currentConfig().baseUrl;
        } catch (Exception ignore) {
            // 未启用 / 读取失败 —— 留空，由下面的 baseUrl is required 兜底
        }
        String base = (overrideUrl == null || overrideUrl.isBlank()) ? savedBase : overrideUrl.trim();
        if (base == null || base.isBlank()) {
            throw new com.anomalyco.ifinbrain.web.common.BizException(400, "baseUrl is required");
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        // healthPath 仍取已存配置（表单同一区块可改），取不到则用 /health
        String path = "/health";
        try {
            String hp = currentConfig().healthPath;
            if (hp != null && !hp.isBlank()) path = hp.trim();
        } catch (Exception ignore) {
            // 同上，路径用默认值
        }
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
            throw new com.anomalyco.ifinbrain.web.common.BizException(502, "连通失败: " + e.getMessage());
        }
    }

    public List<ExtractItem> extractBatch(List<FilePart> files) {
        if (files == null || files.isEmpty()) return List.of();
        String key = apiKey();
        String path = batchPath();
        // 手工构造 multipart/form-data body：boundary 完全由我们自己生成，
        // body parts 用同一个 boundary，Content-Type header 也用同一个 boundary —— 三者一致，
        // 避免 Spring 7 + WebClient 链路下 BodyInserters 的 writer 自己生成 boundary 与
        // 显式 header 不同步导致 fapiao_cn 端 multipart 解析失败（"No files provided" → 400）。
        String boundary = "----ifinbrain" + UUID.randomUUID().toString().replace("-", "");
        String contentType = "multipart/form-data; boundary=" + boundary;
        byte[] body = buildMultipartBody(files, boundary, "files");
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(currentConfig().baseUrl + path))
                    .timeout(Duration.ofSeconds(timeoutSeconds()))
                    .header("Authorization", key.isBlank() ? "" : "Bearer " + key)
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> resp = jdkClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                ObjectMapper om = new ObjectMapper();
                return om.readValue(resp.body(), new TypeReference<List<ExtractItem>>() {});
            }
            log.error("FapiaoCn {} failed: HTTP {} body={}", path, resp.statusCode(), resp.body());
            return files.stream().map(f -> ExtractItem.error(f.filename,
                    "HTTP " + resp.statusCode() + ": " + (resp.body() == null ? "" : resp.body()))).toList();
        } catch (java.io.IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("FapiaoCn {} failed: {}", path, ex.toString());
            return files.stream().map(f -> ExtractItem.error(f.filename, ex.getMessage())).toList();
        }
    }

    public ExtractItem extractSingle(FilePart f) {
        String key = apiKey();
        String path = extractPath();
        String boundary = "----ifinbrain" + UUID.randomUUID().toString().replace("-", "");
        String contentType = "multipart/form-data; boundary=" + boundary;
        byte[] body = buildMultipartBody(List.of(f), boundary, "file");
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(currentConfig().baseUrl + path))
                    .timeout(Duration.ofSeconds(timeoutSeconds()))
                    .header("Authorization", key.isBlank() ? "" : "Bearer " + key)
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> resp = jdkClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return new ObjectMapper().readValue(resp.body(), ExtractItem.class);
            }
            log.error("FapiaoCn {} failed: HTTP {} body={}", path, resp.statusCode(), resp.body());
            return ExtractItem.error(f.filename, "HTTP " + resp.statusCode() + ": " + resp.body());
        } catch (java.io.IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("FapiaoCn {} failed: {}", path, ex.toString());
            return ExtractItem.error(f.filename, ex.getMessage());
        }
    }

    /**
     * 手工构造 multipart/form-data body：boundary 与 Content-Type header 中的 boundary 严格一致。
     * body 用 ISO-8859-1 编码 header 段（二进制字段直接用 ISO-8859-1 bytes，安全无歧义）。
     */
    private static byte[] buildMultipartBody(List<FilePart> files, String boundary, String fieldName) {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try {
            String CRLF = "\r\n";
            java.nio.charset.Charset iso = java.nio.charset.StandardCharsets.ISO_8859_1;
            for (FilePart f : files) {
                bos.write(("--" + boundary + CRLF).getBytes(iso));
                bos.write(("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\""
                        + (f.filename == null ? "file" : f.filename) + "\"" + CRLF).getBytes(iso));
                bos.write(("Content-Type: application/octet-stream" + CRLF + CRLF).getBytes(iso));
                bos.write(f.bytes);
                bos.write(CRLF.getBytes(iso));
            }
            bos.write(("--" + boundary + "--" + CRLF).getBytes(iso));
        } catch (java.io.IOException ex) {
            throw new RuntimeException("build multipart body failed", ex);
        }
        return bos.toByteArray();
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
        /**
         * 费用类别（接口字段 expense_category_label，SnakeCase 策略自动映射）。
         *
         * <p>老版本 fapiao_cn 不返回该字段 —— 留 null 即可，Jackson 不会报错。
         */
        public String expenseCategoryLabel;

        public static ExtractItem error(String fn, String msg) {
            ExtractItem i = new ExtractItem();
            i.file = fn;
            i.message = "error: " + msg;
            return i;
        }
    }
}