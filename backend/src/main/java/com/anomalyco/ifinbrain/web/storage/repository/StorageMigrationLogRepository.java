package com.anomalyco.ifinbrain.web.storage.repository;

import com.anomalyco.ifinbrain.web.storage.entity.StorageMigrationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StorageMigrationLogRepository extends JpaRepository<StorageMigrationLog, Long> {

    long countByFromTypeAndToTypeAndStatus(String fromType, String toType, Short status);

    long countByFromTypeAndToType(String fromType, String toType);

    /** 幂等查重：同 (key, from, to, status=ok) → 视为已迁。 */
    boolean existsByStorageKeyAndStatusAndFromTypeAndToType(
            String storageKey, Short status, String fromType, String toType);

    /** 最新一条日志（用于 admin 端显示 lastError / 最近失败）。 */
    Optional<StorageMigrationLog> findTopByOrderByStartedAtDesc();
}