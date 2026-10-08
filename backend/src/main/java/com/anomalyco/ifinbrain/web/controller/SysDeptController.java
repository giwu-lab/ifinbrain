package com.anomalyco.ifinbrain.web.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.dto.dept.DeptReqs;
import com.anomalyco.ifinbrain.web.service.SysDeptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 组织架构（部门）。
 *
 * <p>{@code GET /tree} 早被前端消费：流程审批节点的组织选取（ApprovalNodeConfig.vue）
 * 与表单 DEPT 字段（DeptField.vue）都在调它，此前该端点并不存在。
 */
@RestController
@RequestMapping("/api/system/depts")
@RequiredArgsConstructor
public class SysDeptController {

    private final SysDeptService service;

    /** 部门树（含 label / value 别名，供 el-tree-select 直接消费） */
    @GetMapping("/tree")
    public Result<List<DeptReqs.Node>> tree() {
        return Result.ok(service.tree());
    }

    @GetMapping
    public Result<List<DeptReqs.Node>> list() {
        return Result.ok(service.flatList());
    }

    @GetMapping("/{id}")
    public Result<DeptReqs.Node> detail(@PathVariable Long id) {
        return Result.ok(service.detail(id));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody DeptReqs.Create req) {
        return Result.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody DeptReqs.Update req) {
        service.update(id, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
