package com.anomalyco.ifinbrain.web.documents.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.documents.dto.ArchiveLibraryCreateReq;
import com.anomalyco.ifinbrain.web.documents.dto.ArchiveLibraryDto;
import com.anomalyco.ifinbrain.web.documents.dto.ArchiveLibraryUpdateReq;
import com.anomalyco.ifinbrain.web.documents.service.ArchiveLibraryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 档案库（原「类目」）Controller。
 * REST 端点：/api/archives
 */
@RestController
@RequestMapping("/api/archives")
@RequiredArgsConstructor
public class ArchiveLibraryController {

    private final ArchiveLibraryService service;

    @GetMapping
    public Result<List<ArchiveLibraryDto>> list() {
        return Result.ok(service.list());
    }

    @GetMapping("/{id}")
    public Result<ArchiveLibraryDto> detail(@PathVariable Long id) {
        return Result.ok(service.detail(id));
    }

    @PostMapping
    public Result<ArchiveLibraryDto> create(@Valid @RequestBody ArchiveLibraryCreateReq req) {
        return Result.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public Result<ArchiveLibraryDto> update(@PathVariable Long id,
                                             @Valid @RequestBody ArchiveLibraryUpdateReq req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }

    /**
     * 档案库 4 个权限码（view/upload/update/delete）当前绑定的角色列表。
     * 返回 Map<verb, List<roleId>> —— 供前端授权对话框预填勾选状态。
     */
    @GetMapping("/{id}/permissions")
    public Result<Map<String, List<Long>>> permissions(@PathVariable Long id) {
        return Result.ok(service.getPermissions(id));
    }

    /**
     * 全量替换档案库 4 个权限码的角色绑定。
     * Body: { view: [roleIds], upload: [roleIds], update: [roleIds], delete: [roleIds] }
     * 注意：admin 角色在 4 个 perm 上的绑定永远保留（避免 admin 自我锁死）。
     */
    @PutMapping("/{id}/permissions")
    public Result<Void> updatePermissions(@PathVariable Long id,
                                           @RequestBody Map<String, List<Long>> grants) {
        service.updatePermissions(id, grants);
        return Result.ok();
    }
}
