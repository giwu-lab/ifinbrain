package com.anomalyco.fapiao.web.storage;

import com.anomalyco.fapiao.web.config.FapiaoWebProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.*;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalStorageService implements StorageService {

    private final FapiaoWebProperties props;
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
}