package com.anomalyco.ifinbrain.web.storage;

import com.anomalyco.ifinbrain.web.config.iFinBrainProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.*;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalStorageService implements StorageService {

    private final iFinBrainProperties props;
    private Path root;

    private Path root() {
        if (root == null) {
            root = Paths.get(props.getStorage().getLocal().getRoot()).toAbsolutePath().normalize();
            try {
                Files.createDirectories(root);
            } catch (Exception ex) {
                throw new RuntimeException("Cannot create storage root: " + root, ex);
            }
        }
        return root;
    }

    @Override
    public String type() { return "local"; }

    @Override
    public void put(String key, InputStream in, long size, String contentType) {
        Path target = root().resolve(key).normalize();
        if (!target.startsWith(root())) {
            throw new SecurityException("Invalid storage key: " + key);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            throw new RuntimeException("Local storage put failed: " + key, ex);
        }
    }

    @Override
    public InputStream get(String key) {
        try {
            return Files.newInputStream(root().resolve(key));
        } catch (Exception ex) {
            throw new RuntimeException("Local storage get failed: " + key, ex);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(root().resolve(key));
        } catch (Exception ex) {
            log.warn("Local delete failed: {}", key, ex);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(root().resolve(key));
    }

    @Override
    public String getPresignedUrl(String key, Duration ttl) {
        // Local storage does not support presigned URLs; callers stream via /api/files/raw.
        return null;
    }

    @Override
    public void ensurePrefix(String prefix) {
        Path target = root().resolve(prefix).normalize();
        if (!target.startsWith(root())) {
            throw new SecurityException("Invalid storage prefix: " + prefix);
        }
        try {
            Files.createDirectories(target);
            // 放一个 0 字节 ".folder" 标记文件 —— 用与 S3 placeholder 同等的语义
            // 区分"真目录"和上传文件时自动产生的 yyyy/MM/ 目录。
            Path marker = target.resolve(".folder");
            if (!Files.exists(marker)) {
                Files.createFile(marker);
            }
            log.debug("Local ensurePrefix: created {} (with .folder marker)", target);
        } catch (Exception ex) {
            throw new RuntimeException("Local storage ensurePrefix failed: " + prefix, ex);
        }
    }

    @Override
    public void deletePrefix(String prefix) {
        Path target = root().resolve(prefix).normalize();
        if (!target.startsWith(root())) {
            throw new SecurityException("Invalid storage prefix: " + prefix);
        }
        if (!Files.exists(target)) return;
        // 安全检查：目录树下还有任何"文件"吗？（空子目录允许，是上传自动创建的空骨架）
        try (var stream = Files.walk(target)) {
            boolean hasFile = stream.anyMatch(Files::isRegularFile);
            if (hasFile) {
                log.warn("Local deletePrefix skipped (still has files): {}", target);
                return;
            }
        } catch (Exception ex) {
            log.warn("Local deletePrefix walk failed: {}", target, ex);
            return;
        }
        // 没有文件 → 递归删除（仅含空子目录，安全）
        try (var stream = Files.walk(target)) {
            stream.sorted(Comparator.reverseOrder()) // 深层在前
                  .forEach(p -> {
                      try { Files.delete(p); }
                      catch (Exception ex) { log.warn("Local deletePrefix: failed to delete {}: {}", p, ex.getMessage()); }
                  });
            log.info("Local deletePrefix: removed {}", target);
        } catch (Exception ex) {
            log.warn("Local deletePrefix recursive delete failed: {}", target, ex);
        }
    }

    /**
     * local 上"真子目录" = 含 .folder 标记文件的目录。
     * 用 marker 区分上传文件时自动产生的 yyyy/MM/ 目录（无 marker）。
     */
    @Override
    public List<String> listFolders(String prefix) {
        Path target = root().resolve(prefix).normalize();
        if (!target.startsWith(root())) {
            throw new SecurityException("Invalid storage prefix: " + prefix);
        }
        if (!Files.exists(target)) return List.of();
        try (var stream = Files.list(target)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(p -> Files.exists(p.resolve(".folder")))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (Exception ex) {
            log.warn("Local listFolders failed for {}: {}", target, ex.getMessage());
            return List.of();
        }
    }
}