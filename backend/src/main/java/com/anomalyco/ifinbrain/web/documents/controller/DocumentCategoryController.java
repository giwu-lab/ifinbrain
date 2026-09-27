package com.anomalyco.ifinbrain.web.documents.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.documents.dto.DocumentCategoryCreateReq;
import com.anomalyco.ifinbrain.web.documents.dto.DocumentCategoryDto;
import com.anomalyco.ifinbrain.web.documents.dto.DocumentCategoryUpdateReq;
import com.anomalyco.ifinbrain.web.documents.service.DocumentCategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/document-categories")
@RequiredArgsConstructor
public class DocumentCategoryController {

    private final DocumentCategoryService service;

    @GetMapping
    public Result<List<DocumentCategoryDto>> list() {
        return Result.ok(service.list());
    }

    @GetMapping("/{id}")
    public Result<DocumentCategoryDto> detail(@PathVariable Long id) {
        return Result.ok(service.detail(id));
    }

    @PostMapping
    public Result<DocumentCategoryDto> create(@Valid @RequestBody DocumentCategoryCreateReq req) {
        return Result.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public Result<DocumentCategoryDto> update(@PathVariable Long id,
                                               @Valid @RequestBody DocumentCategoryUpdateReq req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }

    /**
     * 类目 4 个权限码（view/upload/update/delete）当前绑定的角色列表。
     * 返回 Map<verb, List<roleId>> —— 供前端授权对话框预填勾选状态。
     */
    @GetMapping("/{id}/permissions")
    public Result<Map<String, List<Long>>> permissions(@PathVariable Long id) {
        return Result.ok(service.getPermissions(id));
    }

    /**
     * 全量替换类目 4 个权限码的角色绑定。
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
