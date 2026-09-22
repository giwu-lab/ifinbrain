package com.anomalyco.fapiao.web.controller;

import com.anomalyco.fapiao.web.common.Result;
import com.anomalyco.fapiao.web.entity.SysMenu;
import com.anomalyco.fapiao.web.service.MenuService;
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

    @PostMapping
    public Result<Long> create(@RequestBody SysMenu menu) {
        return Result.ok(menuService.create(menu));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SysMenu menu) {
        menuService.update(id, menu);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        menuService.delete(id);
        return Result.ok();
    }
}