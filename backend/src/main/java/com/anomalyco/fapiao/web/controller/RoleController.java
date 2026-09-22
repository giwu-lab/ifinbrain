package com.anomalyco.fapiao.web.controller;

import com.anomalyco.fapiao.web.common.Result;
import com.anomalyco.fapiao.web.dto.role.RoleCreateReq;
import com.anomalyco.fapiao.web.dto.role.RoleListItem;
import com.anomalyco.fapiao.web.dto.role.RoleUpdateReq;
import com.anomalyco.fapiao.web.security.PermissionChecker;
import com.anomalyco.fapiao.web.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;
    private final PermissionChecker perm;

    @GetMapping
    public Result<List<RoleListItem>> list() {
        perm.require("system:role:list");
        return Result.ok(roleService.list());
    }

    @GetMapping("/{id}")
    public Result<RoleListItem> detail(@PathVariable Long id) {
        perm.require("system:role:list");
        return Result.ok(roleService.detail(id));
    }

    @GetMapping("/permissions")
    public Result<List<Map<String, Object>>> allPermissions() {
        perm.require("system:perm:list");
        return Result.ok(roleService.allPermissions());
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody RoleCreateReq req) {
        perm.require("system:role:create");
        return Result.ok(roleService.create(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody RoleUpdateReq req) {
        perm.require("system:role:update");
        roleService.update(id, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        perm.require("system:role:delete");
        roleService.delete(id);
        return Result.ok();
    }
}