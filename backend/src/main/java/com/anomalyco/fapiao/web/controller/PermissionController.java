package com.anomalyco.fapiao.web.controller;

import com.anomalyco.fapiao.web.common.Result;
import com.anomalyco.fapiao.web.security.PermissionChecker;
import com.anomalyco.fapiao.web.service.RoleService;
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
        perm.require("system:perm:list");
        return Result.ok(roleService.allPermissions());
    }
}