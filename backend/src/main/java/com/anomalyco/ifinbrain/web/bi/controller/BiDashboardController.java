package com.anomalyco.ifinbrain.web.bi.controller;

import com.anomalyco.ifinbrain.web.bi.dto.BiDashboardCreateReq;
import com.anomalyco.ifinbrain.web.bi.dto.BiDashboardDto;
import com.anomalyco.ifinbrain.web.bi.dto.BiDashboardUpdateReq;
import com.anomalyco.ifinbrain.web.bi.service.BiDashboardService;
import com.anomalyco.ifinbrain.web.common.PageReq;
import com.anomalyco.ifinbrain.web.common.PageResp;
import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bi/dashboard")
@RequiredArgsConstructor
public class BiDashboardController {

    private final BiDashboardService dashboardService;
    private final PermissionChecker perm;

    /** 列表页：真分页，支持 keyword（名称 / 编码 / 描述）与 sortBy+sortDir */
    @GetMapping
    public Result<PageResp<BiDashboardDto>> list(PageReq req) {
        return Result.ok(dashboardService.page(req));
    }

    /** 只看已发布（轮播 / 展示墙用） */
    @GetMapping("/published")
    public Result<List<BiDashboardDto>> listPublished() {
        return Result.ok(dashboardService.listPublished());
    }

    @GetMapping("/{id}")
    public Result<BiDashboardDto> detail(@PathVariable Long id) {
        return Result.ok(dashboardService.detail(id));
    }

    @PostMapping
    public Result<BiDashboardDto> create(@Valid @RequestBody BiDashboardCreateReq req) {
        return Result.ok(dashboardService.create(req, perm.currentUsername()));
    }

    @PutMapping("/{id}")
    public Result<BiDashboardDto> update(@PathVariable Long id, @RequestBody BiDashboardUpdateReq req) {
        return Result.ok(dashboardService.update(id, req));
    }

    /** 复制（code 派生 _copy），不带 body */
    @PostMapping("/{id}/copy")
    public Result<BiDashboardDto> copy(@PathVariable Long id) {
        return Result.ok(dashboardService.copy(id, perm.currentUsername()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dashboardService.delete(id);
        return Result.ok();
    }

    @PostMapping("/{id}/publish")
    public Result<BiDashboardDto> publish(@PathVariable Long id) {
        return Result.ok(dashboardService.publish(id));
    }

    @PostMapping("/{id}/unpublish")
    public Result<BiDashboardDto> unpublish(@PathVariable Long id) {
        return Result.ok(dashboardService.unpublish(id));
    }
}
