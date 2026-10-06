package com.anomalyco.ifinbrain.web.design.flow.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.design.flow.dto.ApproveTaskReq;
import com.anomalyco.ifinbrain.web.design.flow.dto.FlowTaskDto;
import com.anomalyco.ifinbrain.web.design.flow.engine.FlowEngineService;
import com.anomalyco.ifinbrain.web.design.flow.service.FlowTaskService;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/design/flow-tasks")
@RequiredArgsConstructor
public class FlowTaskController {

    private final FlowEngineService engine;
    private final FlowTaskService taskService;
    private final PermissionChecker perm;

    /** 当前用户的待办任务 */
    @GetMapping("/pending")
    public Result<List<FlowTaskDto>> pending() {
        perm.require("design:flow:deploy");   // 启动流程相关 perm（与 mfish 对齐：审批是 deploy 范畴）
        List<FlowTaskDto> out = engine.pendingTasks(perm.currentUsername())
                .stream().map(t -> taskService.detail(t.getId())).toList();
        return Result.ok(out);
    }

    /**
     * 当前用户的待办任务数 —— dashboard 卡片用。
     * 不限 perm：登录用户都能看自己的待办数（与 dashboard「角色数/权限数/菜单数」一致，
     * 这些都是个人档案性质的数据，不需要业务 perm）。
     */
    @GetMapping("/pending/count")
    public Result<Long> pendingCount() {
        return Result.ok(taskService.pendingCount(perm.currentUsername()));
    }

    /** 我发起的 + 审批过的所有任务 */
    @GetMapping("/all")
    public Result<List<FlowTaskDto>> all() {
        perm.require("design:flow:deploy");
        List<FlowTaskDto> out = engine.allTasks(perm.currentUsername())
                .stream().map(t -> taskService.detail(t.getId())).toList();
        return Result.ok(out);
    }

    @GetMapping("/{id}")
    public Result<FlowTaskDto> detail(@PathVariable Long id) {
        perm.require("design:flow:deploy");
        return Result.ok(taskService.detail(id));
    }

    @PostMapping("/{id}/approve")
    public Result<FlowTaskDto> approve(@PathVariable Long id, @RequestBody(required = false) ApproveTaskReq req) {
        perm.require("design:flow:deploy");
        engine.approve(id, req == null ? new ApproveTaskReq() : req, perm.currentUsername());
        return Result.ok(taskService.detail(id));
    }

    @PostMapping("/{id}/reject")
    public Result<FlowTaskDto> reject(@PathVariable Long id, @RequestBody(required = false) ApproveTaskReq req) {
        perm.require("design:flow:deploy");
        engine.reject(id, req == null ? new ApproveTaskReq() : req, perm.currentUsername());
        return Result.ok(taskService.detail(id));
    }
}