package com.anomalyco.ifinbrain.web.documents.repository;

import com.anomalyco.ifinbrain.web.documents.entity.DocumentRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentRecordRepository extends JpaRepository<DocumentRecord, Long> {

    Page<DocumentRecord> findByCategoryIdAndStatusOrderByCreatedAtDesc(Long categoryId, Short status, Pageable pageable);

    long countByCategoryIdAndStatus(Long categoryId, Short status);

    Optional<DocumentRecord> findFirstBySha256AndCategoryIdAndStatus(String sha256, Long categoryId, Short status);

    /** 同 sha + 同 storage_key 前缀（= 同目录） → 视为重复。
     *  prefix 例 "documents/cw/demo/" —— 命中即同目录内已有同名同内容档案。 */
    Optional<DocumentRecord> findFirstBySha256AndStorageKeyStartingWith(String sha256, String storageKeyPrefix);

    /** 列某档案库下 storage_key 以 prefix 开头的所有档案（用于按目录过滤）。
     *  深度筛选由 service 层用 slashCount 处理（避免把子目录里的也带出来）。 */
    List<DocumentRecord> findByCategoryIdAndStorageKeyStartingWith(Long categoryId, String prefix);
}
