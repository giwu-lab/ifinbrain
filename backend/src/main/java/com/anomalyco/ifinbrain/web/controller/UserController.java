package com.anomalyco.ifinbrain.web.controller;

import com.anomalyco.ifinbrain.web.common.PageReq;
import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.dto.user.UserCreateReq;
import com.anomalyco.ifinbrain.web.dto.user.UserListItem;
import com.anomalyco.ifinbrain.web.dto.user.UserUpdateReq;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.anomalyco.ifinbrain.web.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final PermissionChecker perm;

    @GetMapping
    public Result<Map<String, Object>> list(PageReq req) {
        perm.require("system:user:list");
        Page<UserListItem> p = userService.page(req);
        Map<String, Object> data = new HashMap<>();
        data.put("items", p.getContent());
        data.put("total", p.getTotalElements());
        data.put("page", req.getPage());
        data.put("size", req.getSize());
        return Result.ok(data);
    }

    @GetMapping("/{id}")
    public Result<UserListItem> detail(@PathVariable Long id) {
        perm.require("system:user:list");
        return Result.ok(userService.detail(id));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody UserCreateReq req) {
        perm.require("system:user:create");
        return Result.ok(userService.create(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody UserUpdateReq req) {
        perm.require("system:user:update");
        userService.update(id, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        perm.require("system:user:delete");
        userService.delete(id);
        return Result.ok();
    }
}