package com.anomalyco.ifinbrain.web.design.flow.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.design.flow.dto.FlowDefineCreateReq;
import com.anomalyco.ifinbrain.web.design.flow.dto.FlowDefineDto;
import com.anomalyco.ifinbrain.web.design.flow.dto.FlowDefineUpdateReq;
import com.anomalyco.ifinbrain.web.design.flow.service.FlowDefineService;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/design/flows")
@RequiredArgsConstructor
public class FlowDefineController {

    private final FlowDefineService service;
    private final PermissionChecker perm;

    @GetMapping
    public Result<List<FlowDefineDto>> list() {
        return Result.ok(service.list());
    }

    @GetMapping("/{id}")
    public Result<FlowDefineDto> detail(@PathVariable Long id) {
        return Result.ok(service.detail(id));
    }

    @GetMapping("/by-code/{code}")
    public Result<FlowDefineDto> detailByCode(@PathVariable String code) {
        return Result.ok(service.detailByCode(code));
    }

    /**
     * 当前用户「可发起」的流程清单：仅 status=1 + 当前用户有流程级 deploy perm 的流程。
     * perm 兜底 design:flow:view —— 看不到 list 则全部看空。
     * 后端权威 —— 避免前端 perm filter 失守（被绕过前端即可发起任意流程）。
     */
    @GetMapping("/launchable")
    public Result<List<FlowDefineDto>> launchable() {
        return Result.ok(service.listLaunchableForUser(perm.currentUsername()));
    }

    @PostMapping
    public Result<FlowDefineDto> create(@Valid @RequestBody FlowDefineCreateReq req) {
        return Result.ok(service.create(req, perm.currentUsername()));
    }

    @PutMapping("/{id}")
    public Result<FlowDefineDto> update(@PathVariable Long id,
                                        @RequestBody FlowDefineUpdateReq req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }

    @PostMapping("/{id}/publish")
    public Result<FlowDefineDto> publish(@PathVariable Long id) {
        return Result.ok(service.publish(id));
    }

    @PostMapping("/{id}/unpublish")
    public Result<FlowDefineDto> unpublish(@PathVariable Long id) {
        return Result.ok(service.unpublish(id));
    }

    /**
     * 查询流程的 4 个 verb 权限当前绑定到哪些角色。
     * 返回 Map<verb, List<roleId>> —— 供前端授权对话框预填勾选状态。
     */
    @GetMapping("/{id}/permissions")
    public Result<Map<String, List<Long>>> permissions(@PathVariable Long id) {
        return Result.ok(service.getPermissions(id));
    }

    /**
     * 全量替换流程 4 个权限码的角色绑定。
     * Body: { view: [roleIds], update: [roleIds], deploy: [roleIds], delete: [roleIds] }
     * 注意：admin 角色在 4 个 perm 上的绑定永远保留（避免 admin 自我锁死）。
     */
    @PutMapping("/{id}/permissions")
    public Result<Void> updatePermissions(@PathVariable Long id,
                                           @RequestBody Map<String, List<Long>> grants) {
        service.updatePermissions(id, grants);
        return Result.ok();
    }
}