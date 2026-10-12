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

    /**
     * 统计某档案库下的档案总行数，**不分 status**。
     *
     * <p>与 {@link #countByCategoryIdAndStatus} 的区别正是在 MISSING(4) / PENDING_*(2,3)：
     * 那些行在列表页看不见（list 只查 READY），却是 FK 上真实的引用者。
     * 删库守卫必须区分「可见档案」与「残留行」—— 前者要拦，后者该级联清掉。
     */
    long countByCategoryId(Long categoryId);

    Optional<DocumentRecord> findFirstBySha256AndCategoryIdAndStatus(String sha256, Long categoryId, Short status);

    /** 同 sha + 同 storage_key 前缀（= 同目录） → 视为重复。
     *  prefix 例 "documents/cw/demo/" —— 命中即同目录内已有同名同内容档案。 */
    Optional<DocumentRecord> findFirstBySha256AndStorageKeyStartingWith(String sha256, String storageKeyPrefix);

    /** 列某档案库下 storage_key 以 prefix 开头的所有档案（用于按目录过滤）。
     *  深度筛选由 service 层用 slashCount 处理（避免把子目录里的也带出来）。 */
    List<DocumentRecord> findByCategoryIdAndStorageKeyStartingWith(Long categoryId, String prefix);

    /**
     * 当前目录（prefix）的 immediate 文件，分页。
     *
     * <p>prefix 固定以 {@code /} 结尾（如 {@code documents/cw/demo/}），于是：
     * <ul>
     *   <li>{@code like prefix%} —— 命中该目录下（含子目录）的全部文件</li>
     *   <li>{@code not like prefix%/%} —— 排除「prefix 之后还带斜杠」的，即子目录里的文件</li>
     * </ul>
     * 这两个条件以前是在 service 里用 {@code contains("/")} 对全量结果逐行过滤的 ——
     * 那意味着一个库有 5000 个文件时，进任何目录都要先把 5000 行捞进内存再丢掉大部分。
     * 下推到 SQL 后过滤与分页一次完成。
     *
     * <p>排序固定 createdAt DESC：与改版前的行为一致（{@code DocumentBrowseDto} 注释也这么写）。
     */
    @Query("SELECT d FROM DocumentRecord d WHERE d.categoryId = :categoryId "
            + "AND d.storageKey LIKE CONCAT(:prefix, '%') "
            + "AND d.storageKey NOT LIKE CONCAT(:prefix, '%/%') "
            + "ORDER BY d.createdAt DESC")
    Page<DocumentRecord> findImmediateFiles(@Param("categoryId") Long categoryId,
                                            @Param("prefix") String prefix,
                                            Pageable pageable);

    /**
     * 仅查 storage_type（投影查询，不取整行）—— 给 FileController 按文档路由到正确后端。
     * 不存在的 key（脏数据 / 已删）由调用方 fallback 到 storage.current()。
     * 投影 SELECT 节省 DB IO；row 字段多、读全文浪费。
     */
    @Query("SELECT d.storageType FROM DocumentRecord d WHERE d.storageKey = :key")
    Optional<String> findStorageTypeByKey(@Param("key") String key);
}
