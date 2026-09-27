package com.anomalyco.ifinbrain.web.design.portal.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.design.portal.dto.PortalPageCreateReq;
import com.anomalyco.ifinbrain.web.design.portal.dto.PortalPageDto;
import com.anomalyco.ifinbrain.web.design.portal.dto.PortalPageUpdateReq;
import com.anomalyco.ifinbrain.web.design.portal.dto.PortalResolveResp;
import com.anomalyco.ifinbrain.web.design.portal.service.PortalPageService;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class PortalPageController {

    private final PortalPageService service;
    private final PermissionChecker perm;

    @GetMapping("/api/design/portal/pages")
    public Result<List<PortalPageDto>> list() {
        return Result.ok(service.list());
    }

    @GetMapping("/api/design/portal/pages/{id}")
    public Result<PortalPageDto> detail(@PathVariable Long id) {
        return Result.ok(service.detail(id));
    }

    @PostMapping("/api/design/portal/pages")
    public Result<PortalPageDto> create(@Valid @RequestBody PortalPageCreateReq req) {
        String createdBy = perm.currentUsername();
        return Result.ok(service.create(req, createdBy));
    }

    @PutMapping("/api/design/portal/pages/{id}")
    public Result<PortalPageDto> update(@PathVariable Long id, @Valid @RequestBody PortalPageUpdateReq req) {
        return Result.ok(service.update(id, req));
    }

    @PostMapping("/api/design/portal/pages/{id}/publish")
    public Result<PortalPageDto> publish(@PathVariable Long id) {
        return Result.ok(service.publish(id));
    }

    @PostMapping("/api/design/portal/pages/{id}/unpublish")
    public Result<PortalPageDto> unpublish(@PathVariable Long id) {
        return Result.ok(service.unpublish(id));
    }

    @DeleteMapping("/api/design/portal/pages/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }

    /**
     * 运行时：登录用户根据角色命中第一个已发布门户页。
     * 不需 design:page:view（任何登录用户都应可消费门户页）。
     */
    @GetMapping("/api/portal/resolve")
    public Result<PortalResolveResp> resolve() {
        return Result.ok(service.resolveForCurrentUser());
    }
}