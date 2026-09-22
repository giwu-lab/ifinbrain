package com.anomalyco.fapiao.web.storage;

import com.anomalyco.fapiao.web.entity.SysSetting;
import com.anomalyco.fapiao.web.repository.SysSettingRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class StorageSelector {

    public static class StorageChangedEvent {}

    private final LocalStorageService local;
    private final S3StorageService s3;
    private final SysSettingRepository settingRepo;
    private final ApplicationEventPublisher publisher;
    private volatile StorageService current;

    @PostConstruct
    void init() {
        // Lazy - settings table may not exist yet during init if Flyway hasn't finished.
        // First access will trigger reload().
        current = local;
    }

    private synchronized void ensureLoaded() {
        if (current == null) reload();
    }

    public synchronized void reload() {
        String t;
        try {
            t = settingRepo.findById("storage.type").map(SysSetting::getValueText).orElse("local");
        } catch (Exception ex) {
            log.warn("Settings table not ready yet ({}); falling back to local", ex.getMessage());
            current = local;
            return;
        }
        switch (t) {
            case "s3" -> {
                current = s3;
                s3.invalidate();
                log.info("Storage switched to: s3");
            }
            case "local" -> {
                current = local;
                log.info("Storage switched to: local");
            }
            default -> {
                current = local;
                log.warn("Unknown storage type '{}', falling back to local", t);
            }
        }
    }

    @EventListener(StorageChangedEvent.class)
    public void onChanged() { reload(); }

    public StorageService current() {
        ensureLoaded();
        return current != null ? current : local;
    }

    public String currentType() { return current() != null ? current().type() : "local"; }

    public String presignedOrNull(String key) {
        return current().getPresignedUrl(key, Duration.ofMinutes(15));
    }
}