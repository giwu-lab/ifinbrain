package com.anomalyco.ifinbrain.web.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.anomalyco.ifinbrain.web.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final RoleService roleService;
    private final PermissionChecker perm;

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        // 菜单管理需要在下拉里选权限码， 因此拥有 system:menu:create / :update 的用户也能拉取
        perm.requireAny("system:perm:list", "system:menu:create", "system:menu:update");
        return Result.ok(roleService.allPermissions());
    }
}