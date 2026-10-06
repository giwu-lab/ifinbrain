package com.anomalyco.ifinbrain.web.design.flow.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.design.flow.dto.FlowInstanceDto;
import com.anomalyco.ifinbrain.web.design.flow.dto.StartFlowReq;
import com.anomalyco.ifinbrain.web.design.flow.engine.FlowEngineService;
import com.anomalyco.ifinbrain.web.design.flow.service.FlowInstanceService;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.HashMap;

@RestController
@RequestMapping("/api/design/flow-instances")
@RequiredArgsConstructor
public class FlowInstanceController {

    private final FlowInstanceService instanceService;
    private final FlowEngineService engine;
    private final PermissionChecker perm;

    @GetMapping
    public Result<List<FlowInstanceDto>> list() {
        perm.require("design:flow:view");
        return Result.ok(instanceService.list());
    }

    @GetMapping("/{id}")
    public Result<FlowInstanceDto> detail(@PathVariable Long id) {
        perm.require("design:flow:view");
        return Result.ok(instanceService.detail(id));
    }

    @PostMapping("/start")
    public Result<FlowInstanceDto> start(@Valid @RequestBody StartFlowReq req) {
        // perm 检查：全局 design:flow:deploy OR 流程级 design:flow:{code}:deploy
        // （前端按 perm 过滤列表，后端做权威判断；admin/operator 全局通过；viewer 仅能起被授权的流程）
        perm.requireAny(
            "design:flow:deploy",
            "design:flow:" + req.getCode() + ":deploy"
        );
        engine.start(req, perm.currentUsername());
        // 查找刚启动的 instance（通过 businessKey + flowCode 唯一匹配）
        var instList = instanceService.list();
        return Result.ok(instList.stream()
                .filter(i -> i.getFlowCode().equals(req.getCode()) && i.getBusinessKey().equals(req.getBusinessKey()))
                .reduce((a, b) -> b)   // 取最后一个
                .orElseThrow());
    }

    /**
     * 删除实例。仅 REJECTED 状态 + 发起人或 admin 可操作。
     * 级联清理 task + history。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        perm.require("design:flow:deploy");
        String roles = perm.currentRoleCodes() == null ? "" : perm.currentRoleCodes();
        boolean isAdmin = roles.contains("admin");
        instanceService.delete(id, perm.currentUsername(), isAdmin);
        return Result.ok();
    }

    /** 把 SysFlowInstance 转 DTO 暴露给前端（不重复实现） */
    @SuppressWarnings("unused")
    private FlowInstanceDto toDto(com.anomalyco.ifinbrain.web.design.flow.entity.SysFlowInstance inst) {
        // 走 instanceService.detail 路径
        return instanceService.detail(inst.getId());
    }

    /** 简化：返回空 map 兼容旧 API */
    @SuppressWarnings("unused")
    private HashMap<String, Object> placeholder() { return new HashMap<>(); }
}