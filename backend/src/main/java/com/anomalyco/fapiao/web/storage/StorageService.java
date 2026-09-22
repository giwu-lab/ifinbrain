package com.anomalyco.fapiao.web.storage;

import java.io.InputStream;
import java.time.Duration;

public interface StorageService {
    String type();
    void put(String key, InputStream in, long size, String contentType);
    InputStream get(String key);
    /** 默认实现：把 get(key) 的流读成 byte[]；S3 等实现可覆写为更高效版本。 */
    default byte[] readAllBytes(String key) {
        try (InputStream in = get(key)) { return in.readAllBytes(); }
        catch (java.io.IOException e) { throw new RuntimeException("Storage readAllBytes failed: " + key, e); }
    }
    void delete(String key);
    boolean exists(String key);
    String getPresignedUrl(String key, Duration ttl);
}