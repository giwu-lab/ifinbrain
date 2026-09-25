package com.anomalyco.ifinbrain.web.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

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

    /**
     * 确保以 {@code prefix} 为前缀的"目录"存在。
     * - local：mkdir -p（中间目录不存在则一并创建）
     * - S3：no-op（key prefix 在首次 put 时隐式创建；0-byte 占位文件没必要）
     *
     * 给档案管理的类目用：在创建类目时调用，slug 即成为 storage 上的目录名。
     */
    default void ensurePrefix(String prefix) {
        // 默认 no-op；local 实现覆写
    }

    /**
     * 清理以 {@code prefix} 为前缀的空"目录"（仅当完全空时）。
     * - local：rmdir（若非空则跳过 + warn，不抛异常）
     * - S3：no-op（列举 + 批量删代价高，超出本接口语义）
     */
    default void deletePrefix(String prefix) {
        // 默认 no-op；local 实现覆写
    }

    /**
     * 列出 {@code prefix} 下的"真子目录"名（immediate subdirs only）。
     * - local：{@code Files.list()} 后过滤目录，返回目录名（不含 prefix）
     * - S3：{@code listObjectsV2(Prefix=prefix, Delimiter='/')} 的 CommonPrefixes
     *        —— 但只保留那些有占位对象（0-byte 结尾带 /）的，避免把 yyyy/MM 误判为文件夹
     */
    default List<String> listFolders(String prefix) {
        return List.of();
    }
}