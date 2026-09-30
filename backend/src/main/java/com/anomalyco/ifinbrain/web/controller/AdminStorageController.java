package com.anomalyco.ifinbrain.web.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.storage.dto.MigrateReq;
import com.anomalyco.ifinbrain.web.storage.dto.MigrateStartResp;
import com.anomalyco.ifinbrain.web.storage.dto.MigrateStatusResp;
import com.anomalyco.ifinbrain.web.storage.service.StorageMigrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Admin 端：L2 档案库 storage 后端迁移工具。
 *
 * 用法：
 *   1. POST /api/admin/storage/migrate { fromType, toType, batchSize, dryRun }
 *        → 返回 taskId + 总数 + 待迁数（立即返回；搬运在 @Async 线程跑）
 *   2. GET  /api/admin/storage/migrate/status?fromType=...&toType=...
 *        → 轮询查询进度
 *
 * 权限：documents:delete（仅 admin 默认拥有；其他角色可手动分配）
 *
 * 安全要点：
 *   - 迁移期间不要切 storage.type；先等 status.migrated == status.total 再切
 *   - L3 auto-fallback (storage.fallback.enabled='true') 可在迁移窗口临时开启，让前端访问无缝
 */
@RestController
@RequestMapping("/api/admin/storage")
@RequiredArgsConstructor
public class AdminStorageController {

    private final StorageMigrationService migration;

    @PostMapping("/migrate")
    public Result<MigrateStartResp> start(@Valid @RequestBody MigrateReq req) {
        return Result.ok(migration.startMigration(req));
    }

    @GetMapping("/migrate/status")
    public Result<MigrateStatusResp> status(@RequestParam String fromType,
                                              @RequestParam String toType) {
        return Result.ok(migration.getStatus(fromType, toType));
    }
}