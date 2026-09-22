package com.anomalyco.fapiao.web.storage;

import com.anomalyco.fapiao.web.config.FapiaoWebProperties;
import com.anomalyco.fapiao.web.entity.SysSetting;
import com.anomalyco.fapiao.web.repository.SysSettingRepository;
import com.anomalyco.fapiao.web.security.CryptoUtil;
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

/**
 * S3 兼容存储。配置优先级：sys_setting（运行时可改，密钥加密存储） > application.yml（部署期默认值）。
 * 切换 / 修改后通过 invalidate() 重建 client，无需重启。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3StorageService implements StorageService {

    private final FapiaoWebProperties props;
    private final SysSettingRepository settingRepo;
    private final CryptoUtil crypto;

    private volatile S3Client client;
    private volatile S3Presigner presigner;
    private volatile String cachedBucket;

    /** 生效配置：sys_setting 优先，缺失字段回落到 application.yml */
    private record EffectiveCfg(String endpoint, String region, String bucket,
                                String accessKey, String secretKey, boolean pathStyle) {}

    private EffectiveCfg effectiveConfig() {
        FapiaoWebProperties.Storage.S3 defaults = props.getStorage().getS3();
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

    private S3Presigner presigner() {
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

    @Override
    public String getPresignedUrl(String key, Duration ttl) {
        return presigner().presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(cachedBucket).key(key).build())
                .build()).url().toString();
    }

    public void invalidate() {
        client = null;
        presigner = null;
        cachedBucket = null;
    }
}
