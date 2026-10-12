package com.anomalyco.ifinbrain.web.bi.controller;

import com.anomalyco.ifinbrain.web.bi.dto.BiDashboardCreateReq;
import com.anomalyco.ifinbrain.web.bi.dto.BiDashboardDto;
import com.anomalyco.ifinbrain.web.bi.dto.BiDashboardUpdateReq;
import com.anomalyco.ifinbrain.web.bi.service.BiDashboardService;
import com.anomalyco.ifinbrain.web.common.PageReq;
import com.anomalyco.ifinbrain.web.common.PageResp;
import com.anomalyco.ifinbrain.web.common.PublishMenuReq;
import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    /** 设计器读大屏：要求实体级 design 权限（光有 view 不够 —— 能看 ≠ 能改） */
    @GetMapping("/{id}/design")
    public Result<BiDashboardDto> detailForDesign(@PathVariable Long id) {
        return Result.ok(dashboardService.detailForDesign(id));
    }

    /**
     * 这张大屏 4 个实体级权限码（view/design/publish/delete）当前绑定的角色。
     * 返回 {@code {verb: [roleId]}}，供前端授权对话框预填勾选状态。
     */
    @GetMapping("/{id}/permissions")
    public Result<Map<String, List<Long>>> permissions(@PathVariable Long id) {
        return Result.ok(dashboardService.getPermissions(id));
    }

    /**
     * Body: {@code { view:[roleIds], design:[...], publish:[...], delete:[...] }}
     * 空列表 = 收回全部授权（admin 角色始终保留）。
     */
    @PutMapping("/{id}/permissions")
    public Result<Void> updatePermissions(@PathVariable Long id,
                                           @RequestBody Map<String, List<Long>> grants) {
        dashboardService.updatePermissions(id, grants);
        return Result.ok();
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

    /**
     * 发布 = 翻 status 位（进轮播）+ 在菜单树里建入口（挂在指定目录下）。
     *
     * <p>body 可空：全部字段都有默认值，不填就挂到 /bi 目录、用大屏名做菜单名。
     */
    @PostMapping("/{id}/publish")
    public Result<BiDashboardDto> publish(@PathVariable Long id,
                                          @RequestBody(required = false) PublishMenuReq req) {
        return Result.ok(dashboardService.publish(id, req));
    }

    /** 取消发布：退回草稿（退出轮播）+ 菜单软下线（status=0，可恢复） */
    @PostMapping("/{id}/unpublish")
    public Result<BiDashboardDto> unpublish(@PathVariable Long id) {
        return Result.ok(dashboardService.unpublish(id));
    }

    /** 可选父目录列表（发布对话框的下拉） */
    @GetMapping("/publish-dirs")
    public Result<List<Map<String, Object>>> publishDirs() {
        return Result.ok(dashboardService.publishDirs());
    }
}
