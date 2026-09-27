package com.anomalyco.ifinbrain.web.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.dto.menu.MenuCreateReq;
import com.anomalyco.ifinbrain.web.dto.menu.MenuUpdateReq;
import com.anomalyco.ifinbrain.web.service.MenuService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system/menus")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    @GetMapping("/tree")
    public Result<List<Map<String, Object>>> tree() {
        return Result.ok(menuService.fullTree());
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(menuService.flatList());
    }

    /**
     * 接收专用 DTO（不是 SysMenu 实体）—— 防止前端塞 id / createdAt / createdBy
     * 等系统字段绕过业务校验（review L22）。
     */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody MenuCreateReq req) {
        return Result.ok(menuService.create(req.toEntity()));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody MenuUpdateReq req) {
        menuService.update(id, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        menuService.delete(id);
        return Result.ok();
    }
}