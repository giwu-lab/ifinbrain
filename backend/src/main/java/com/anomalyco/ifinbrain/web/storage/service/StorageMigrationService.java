package com.anomalyco.ifinbrain.web.storage.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.documents.entity.DocumentRecord;
import com.anomalyco.ifinbrain.web.documents.repository.DocumentRecordRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.anomalyco.ifinbrain.web.storage.LocalStorageService;
import com.anomalyco.ifinbrain.web.storage.S3StorageService;
import com.anomalyco.ifinbrain.web.storage.dto.MigrateReq;
import com.anomalyco.ifinbrain.web.storage.dto.MigrateStartResp;
import com.anomalyco.ifinbrain.web.storage.dto.MigrateStatusResp;
import com.anomalyco.ifinbrain.web.storage.entity.StorageMigrationLog;
import com.anomalyco.ifinbrain.web.storage.repository.StorageMigrationLogRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * L2 迁移工具：把 sys_document 的 storage_type 从 'local' 批量搬运到 's3'（或反之）。
 *
 * 核心规则：
 * - 单批同步完成：service.startMigration 立刻返回；实际搬运在 @Async 线程跑
 * - 每行独立事务（REQUIRES_NEW）：单文件失败不阻塞整批
 * - 幂等：status=1 已迁条目跳过（除非 idempotent=false）
 * - dryRun：仅算 SHA + size，不真搬，不写 log
 * - 进度查：通过 GET /api/admin/storage/migrate/status 走 sys_document 总数 + log 聚合
 *
 * 安全：
 * - perm.require("documents:delete")：admin + 高级权限角色可触发
 * - 不在 service 内隐式切全局 storage.type（admin 切后端是单独动作；切之前查 status）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageMigrationService {

    private final DocumentRecordRepository documentRepo;
    private final StorageMigrationLogRepository logRepo;
    private final LocalStorageService localStorage;
    private final S3StorageService s3Storage;
    private final PermissionChecker perm;

    @PersistenceContext
    private EntityManager em;

    /**
     * 启动迁移。同步返回预览（总数 / 已迁 / 待待），实际搬运在 @Async 线程跑。
     */
    public MigrateStartResp startMigration(MigrateReq req) {
        perm.require("documents:delete");
        validateTypes(req);

        long total = documentRepo.count();
        long needMigrate = countByForType(req.getFromType());
        long alreadyDone = logRepo.countByFromTypeAndToTypeAndStatus(
                req.getFromType(), req.getToType(), (short) 1);

        if (!req.isDryRun()) {
            // 异步搬运；返回时已 fire-and-forget
            runMigrationAsync(req);
        }

        log.info("[storage-migrate] start from={} to={} dryRun={} total={} alreadyDone={}",
                req.getFromType(), req.getToType(), req.isDryRun(), needMigrate, alreadyDone);

        return new MigrateStartResp(
                req.getFromType(),
                req.getToType(),
                needMigrate,
                alreadyDone,
                needMigrate - alreadyDone,
                req.isDryRun());
    }

    /**
     * 进度查询：admin 前端轮询用。
     */
    public MigrateStatusResp getStatus(String fromType, String toType) {
        long total = countByForType(fromType);
        long migrated = logRepo.countByFromTypeAndToTypeAndStatus(fromType, toType, (short) 1);
        long failed = logRepo.countByFromTypeAndToTypeAndStatus(fromType, toType, (short) 2);
        long pending = Math.max(0, total - migrated - failed);

        // 最新一条失败的 error_msg（admin 排查用）
        String lastError = logRepo.findTopByOrderByStartedAtDesc()
                .filter(l -> fromType.equals(l.getFromType()) && toType.equals(l.getToType())
                        && l.getStatus() != null && l.getStatus() == 2)
                .map(StorageMigrationLog::getErrorMsg)
                .orElse(null);

        return new MigrateStatusResp(fromType, toType, total, migrated, failed, pending, lastError);
    }

    // ===================== 核心搬运 =====================

    @Async
    public void runMigrationAsync(MigrateReq req) {
        try {
            runMigration(req);
        } catch (Exception ex) {
            log.error("[storage-migrate] async task crashed", ex);
        }
    }

    void runMigration(MigrateReq req) {
        List<DocumentRecord> all = documentRepo.findAll();
        int batchSize = Math.max(1, req.getBatchSize());
        int processed = 0;
        for (DocumentRecord rec : all) {
            if (!req.getFromType().equals(rec.getStorageType())) continue;

            processed++;
            if (processed % batchSize == 0) {
                log.info("[storage-migrate] progress: {}/{}", processed, all.size());
            }

            try {
                migrateOne(req, rec);
            } catch (Exception ex) {
                log.warn("[storage-migrate] skip id={} key={} due to: {}",
                        rec.getId(), rec.getStorageKey(), ex.getMessage());
                // 失败已由 migrateOne 自己写 log
            }
        }
        log.info("[storage-migrate] done from={} to={} dryRun={} processed={}",
                req.getFromType(), req.getToType(), req.isDryRun(), processed);
    }

    /**
     * 单文件迁移：每行独立事务（REQUIRES_NEW），失败不阻塞批。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void migrateOne(MigrateReq req, DocumentRecord rec) {
        // 幂等：已成功跳过
        if (req.isIdempotent() && logRepo.existsByStorageKeyAndStatusAndFromTypeAndToType(
                rec.getStorageKey(), (short) 1, req.getFromType(), req.getToType())) {
            return;
        }

        StorageMigrationLog logRow = StorageMigrationLog.builder()
                .fromType(req.getFromType())
                .toType(req.getToType())
                .storageKey(rec.getStorageKey())
                .status((short) 2)
                .startedAt(LocalDateTime.now())
                .build();
        logRow = logRepo.save(logRow);

        try {
            byte[] bytes;
            try (InputStream in = sourceStorage(req.getFromType()).get(rec.getStorageKey())) {
                bytes = in.readAllBytes();
            } catch (Exception ex) {
                throw new BizException(500, "源后端读取失败：" + ex.getMessage());
            }

            String sha;
            long size = bytes.length;
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                sha = HexFormat.of().formatHex(md.digest(bytes));
            } catch (Exception ex) {
                throw new BizException(500, "SHA 计算失败：" + ex.getMessage());
            }

            if (!req.isDryRun()) {
                try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
                    targetStorage(req.getToType()).put(rec.getStorageKey(), in, size, "application/octet-stream");
                }
                // 写 DB：storage_type 改成目标
                em.createNativeQuery(
                                "UPDATE sys_document SET storage_type = :toType, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                        .setParameter("toType", req.getToType())
                        .setParameter("id", rec.getId())
                        .executeUpdate();
            }

            logRow.setSizeBytes(size);
            logRow.setSha256(sha);
            logRow.markFinished(true, null);

        } catch (BizException ex) {
            logRow.markFinished(false, ex.getMessage());
        } catch (Exception ex) {
            logRow.markFinished(false, ex.getMessage());
        }
        logRepo.save(logRow);
    }

    // ===================== helpers =====================

    private long countByForType(String fromType) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM sys_document WHERE storage_type = :t")
                .setParameter("t", fromType)
                .getSingleResult()).longValue();
    }

    private static void validateTypes(MigrateReq req) {
        if (!"local".equals(req.getFromType()) && !"s3".equals(req.getFromType())) {
            throw new BizException(400, "fromType 仅支持 local / s3");
        }
        if (!"local".equals(req.getToType()) && !"s3".equals(req.getToType())) {
            throw new BizException(400, "toType 仅支持 local / s3");
        }
        if (req.getFromType().equals(req.getToType())) {
            throw new BizException(400, "fromType 与 toType 不可相同");
        }
    }

    private com.anomalyco.ifinbrain.web.storage.StorageService sourceStorage(String type) {
        return "local".equals(type) ? localStorage : s3Storage;
    }

    private com.anomalyco.ifinbrain.web.storage.StorageService targetStorage(String type) {
        return "local".equals(type) ? localStorage : s3Storage;
    }
}