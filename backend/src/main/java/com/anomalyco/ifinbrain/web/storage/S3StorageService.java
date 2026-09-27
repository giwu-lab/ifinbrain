package com.anomalyco.ifinbrain.web.storage;

import com.anomalyco.ifinbrain.web.config.iFinBrainProperties;
import com.anomalyco.ifinbrain.web.entity.SysSetting;
import com.anomalyco.ifinbrain.web.repository.SysSettingRepository;
import com.anomalyco.ifinbrain.web.security.CryptoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * S3 兼容存储。配置优先级：sys_setting（运行时可改，密钥加密存储） > application.yml（部署期默认值）。
 * 切换 / 修改后通过 invalidate() 重建 client，无需重启。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3StorageService implements StorageService {

    private final iFinBrainProperties props;
    private final SysSettingRepository settingRepo;
    private final CryptoUtil crypto;

    private volatile S3Client client;
    private volatile S3Presigner presigner;
    private volatile String cachedBucket;

    /** 生效配置：sys_setting 优先，缺失字段回落到 application.yml */
    public record EffectiveCfg(String endpoint, String region, String bucket,
                               String accessKey, String secretKey, boolean pathStyle) {}

    private EffectiveCfg effectiveConfig() {
        iFinBrainProperties.Storage.S3 defaults = props.getStorage().getS3();
        String endpoint  = getSetting("storage.s3.endpoint",  defaults.getEndpoint());
        String region    = getSetting("storage.s3.region",    defaults.getRegion());
        String bucket    = getSetting("storage.s3.bucket",    defaults.getBucket());
        String accessKey = getSettingDecrypt("storage.s3.accessKey", defaults.getAccessKey());
        String secretKey = getSettingDecrypt("storage.s3.secretKey", defaults.getSecretKey());
        boolean pathStyle = Boolean.parseBoolean(
                getSetting("storage.s3.pathStyle", String.valueOf(defaults.isPathStyle())));
        return new EffectiveCfg(endpoint, region, bucket, accessKey, secretKey, pathStyle);
    }

    private String getSetting(String key, String def) {
        try {
            return settingRepo.findById(key)
                    .map(SysSetting::getValueText)
                    .filter(v -> v != null && !v.isBlank())
                    .orElse(def);
        } catch (Exception e) {
            return def;
        }
    }

    /** 读加密字段：v1: 前缀走 CryptoUtil 解密；否则按明文（兼容旧数据） */
    private String getSettingDecrypt(String key, String def) {
        try {
            String raw = settingRepo.findById(key)
                    .map(SysSetting::getValueText)
                    .orElse(null);
            if (raw == null || raw.isBlank()) return def;
            return crypto.decrypt(raw);
        } catch (Exception e) {
            log.warn("decrypt setting {} failed, falling back to default: {}", key, e.getMessage());
            return def;
        }
    }

    private synchronized S3Client client() {
        if (client == null) {
            EffectiveCfg c = effectiveConfig();
            client = S3Client.builder()
                    .endpointOverride(c.endpoint().isBlank() ? null : URI.create(c.endpoint()))
                    .region(Region.of(c.region()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(c.accessKey(), c.secretKey())))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(c.pathStyle())
                            .build())
                    .build();
            cachedBucket = c.bucket();
            log.info("S3StorageService initialized: endpoint={}, bucket={}, region={}, pathStyle={}",
                    c.endpoint(), c.bucket(), c.region(), c.pathStyle());
        }
        return client;
    }

    private synchronized S3Presigner presigner() {
        if (presigner == null) {
            EffectiveCfg c = effectiveConfig();
            presigner = S3Presigner.builder()
                    .endpointOverride(c.endpoint().isBlank() ? null : URI.create(c.endpoint()))
                    .region(Region.of(c.region()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(c.accessKey(), c.secretKey())))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(c.pathStyle())
                            .build())
                    .build();
        }
        return presigner;
    }

    @Override public String type() { return "s3"; }

    @Override
    public void put(String key, InputStream in, long size, String contentType) {
        client().putObject(PutObjectRequest.builder()
                .bucket(cachedBucket)
                .key(key)
                .contentType(contentType)
                .build(),
                RequestBody.fromInputStream(in, size));
    }

    @Override
    public InputStream get(String key) {
        return client().getObject(GetObjectRequest.builder()
                .bucket(cachedBucket).key(key).build(),
                ResponseTransformer.toInputStream());
    }

    @Override
    public void delete(String key) {
        try {
            client().deleteObject(DeleteObjectRequest.builder()
                    .bucket(cachedBucket).key(key).build());
        } catch (Exception ex) {
            log.warn("S3 delete failed: {}", key, ex);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            client().headObject(HeadObjectRequest.builder().bucket(cachedBucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) { return false; }
        catch (Exception ex) { return false; }
    }

    /**
     * S3 没有真正的「目录」概念 —— 路径只是 key 的 / 分隔符。
     * 为让 S3 console 能看到空目录（点开后才有内容），上传一个 0 字节占位对象，key 末尾带 /。
     * 这是与 local mkdir -p 等价的 S3 语义。
     *
     * 注意：S3Exception 直接向上抛（不包装），由 GlobalExceptionHandler.s3() 统一翻译成可操作提示。
     */
    @Override
    public void ensurePrefix(String prefix) {
        String key = prefix.endsWith("/") ? prefix : prefix + "/";
        try {
            if (exists(key)) {
                log.debug("S3 ensurePrefix: placeholder already exists {}", key);
                return;
            }
        } catch (S3Exception ex) {
            log.error("S3 head-object failed for {}: {}", key, ex.getMessage());
            throw ex; // 让 GlobalExceptionHandler 翻译
        }
        client().putObject(PutObjectRequest.builder()
                .bucket(cachedBucket)
                .key(key)
                .contentLength(0L)
                .build(),
                RequestBody.fromBytes(new byte[0]));
        log.info("S3 ensurePrefix: created placeholder {}", key);
    }

    /**
     * S3 端清理：删除空目录占位对象。如果类目下还有文件（业务校验已拦截），不会误删。
     * 不做"递归删除整个 prefix"——避免误删。
     */
    @Override
    public void deletePrefix(String prefix) {
        String key = prefix.endsWith("/") ? prefix : prefix + "/";
        try {
            client().deleteObject(DeleteObjectRequest.builder()
                    .bucket(cachedBucket).key(key).build());
            log.info("S3 deletePrefix: removed placeholder {}", key);
        } catch (NoSuchKeyException nsk) {
            log.debug("S3 deletePrefix: no placeholder for {}", key);
        } catch (S3Exception ex) {
            log.warn("S3 deletePrefix failed for {}: {}", key, ex.getMessage());
        }
    }

    /**
     * S3 列出 prefix 下的"真子目录"。
     * 用 listObjectsV2 + delimiter='/' 得到 CommonPrefixes。
     * 但 S3 把 yyyy/MM/ 这种"非文件夹"也当成 prefix —— 我们用 placeholder 存在与否过滤：
     * 真目录有 0-byte 占位（key 末尾带 /），yyyy/MM 没占位。
     */
    @Override
    public List<String> listFolders(String prefix) {
        String normalized = prefix.endsWith("/") ? prefix : prefix + "/";
        List<String> result = new java.util.ArrayList<>();
        String token = null;
        try {
            do {
                ListObjectsV2Request.Builder b = ListObjectsV2Request.builder()
                        .bucket(cachedBucket)
                        .prefix(normalized)
                        .delimiter("/");
                if (token != null) b.continuationToken(token);
                ListObjectsV2Response resp = client().listObjectsV2(b.build());
                for (CommonPrefix cp : resp.commonPrefixes()) {
                    // cp.prefix() 形如 "documents/cw/demo/"（带末尾 /）
                    // 占位对象 key 与 cp.prefix() 完全一致 —— 存在即"真目录"
                    String prefixStr = cp.prefix();
                    if (exists(prefixStr)) {
                        String name = prefixStr.substring(normalized.length(), prefixStr.length() - 1);
                        if (!name.isEmpty()) result.add(name);
                    }
                }
                token = resp.nextContinuationToken();
            } while (token != null);
        } catch (Exception ex) {
            log.warn("S3 listFolders failed for {}: {}", normalized, ex.getMessage());
            return List.of();
        }
        java.util.Collections.sort(result);
        return result;
    }

    @Override
    public String getPresignedUrl(String key, Duration ttl) {
        return presigner().presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(cachedBucket).key(key).build())
                .build()).url().toString();
    }

    public void invalidate() {
        S3Client oldClient = client;
        S3Presigner oldPresigner = presigner;
        client = null;
        presigner = null;
        cachedBucket = null;
        // 关掉旧实例释放连接池/线程池；任何关闭调用都不阻断 invalidate 本身
        if (oldClient != null) {
            try { oldClient.close(); } catch (Exception e) {
                log.warn("S3 client close failed: {}", e.getMessage());
            }
        }
        if (oldPresigner != null) {
            try { oldPresigner.close(); } catch (Exception e) {
                log.warn("S3 presigner close failed: {}", e.getMessage());
            }
        }
    }

    /**
     * 测试连通：用给定配置（覆盖）尝试 head bucket。
     * 返回 {ok, message, latencyMs, bucket}。失败时 message 给出根因。
     * 不修改缓存 client / presigner，仅用于 UI 测试连通按钮。
     */
    public Map<String, Object> probe(EffectiveCfg c) {
        long t0 = System.currentTimeMillis();
        S3Client probe = S3Client.builder()
                .endpointOverride(c.endpoint().isBlank() ? null : URI.create(c.endpoint()))
                .region(Region.of(c.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(c.accessKey(), c.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(c.pathStyle())
                        .build())
                .build();
        try {
            probe.headBucket(HeadBucketRequest.builder().bucket(c.bucket()).build());
            long ms = System.currentTimeMillis() - t0;
            return Map.of("ok", true, "message", "OK", "latencyMs", ms, "bucket", c.bucket());
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            log.warn("S3 probe failed: endpoint={}, bucket={}, err={}", c.endpoint(), c.bucket(), e.toString());
            return Map.of("ok", false, "message", rootCauseMessage(e),
                    "latencyMs", ms, "bucket", c.bucket());
        } finally {
            try { probe.close(); } catch (Exception e) {
                log.warn("S3 probe client close failed: {}", e.getMessage());
            }
        }
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String m = cur.getMessage();
        return m != null ? m : cur.getClass().getSimpleName();
    }

    /** 当前生效配置（暴露给 SettingService 用来 probe） */
    public EffectiveCfg currentEffective() { return effectiveConfig(); }
}
