package com.anomalyco.ifinbrain.web.documents.repository;

import com.anomalyco.ifinbrain.web.documents.entity.DocumentRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 仅查 storage_type（投影查询，不取整行）—— 给 FileController 按文档路由到正确后端。
     * 不存在的 key（脏数据 / 已删）由调用方 fallback 到 storage.current()。
     * 投影 SELECT 节省 DB IO；row 字段多、读全文浪费。
     */
    @Query("SELECT d.storageType FROM DocumentRecord d WHERE d.storageKey = :key")
    Optional<String> findStorageTypeByKey(@Param("key") String key);
}
