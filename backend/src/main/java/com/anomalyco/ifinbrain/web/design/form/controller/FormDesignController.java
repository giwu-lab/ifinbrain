package com.anomalyco.ifinbrain.web.design.form.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.design.form.dto.FormDesignCreateReq;
import com.anomalyco.ifinbrain.web.design.form.dto.FormDesignDto;
import com.anomalyco.ifinbrain.web.design.form.dto.FormDesignUpdateReq;
import com.anomalyco.ifinbrain.web.design.form.service.FormDesignService;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/design/forms")
@RequiredArgsConstructor
public class FormDesignController {

    private final FormDesignService service;
    private final PermissionChecker perm;

    @GetMapping
    public Result<List<FormDesignDto>> list() {
        return Result.ok(service.list());
    }

    @GetMapping("/{id}")
    public Result<FormDesignDto> detail(@PathVariable Long id) {
        return Result.ok(service.detail(id));
    }

    /**
     * 按 code 查询 —— 主要给流程设计器挂表单时使用。
     * 需要 view perm（普通用户也可能要预览）。
     */
    @GetMapping("/by-code/{code}")
    public Result<FormDesignDto> detailByCode(@PathVariable String code) {
        return Result.ok(service.detailByCode(code));
    }

    @PostMapping
    public Result<FormDesignDto> create(@Valid @RequestBody FormDesignCreateReq req) {
        return Result.ok(service.create(req, perm.currentUsername()));
    }

    @PutMapping("/{id}")
    public Result<FormDesignDto> update(@PathVariable Long id,
                                        @RequestBody FormDesignUpdateReq req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }

    @PostMapping("/{id}/publish")
    public Result<FormDesignDto> publish(@PathVariable Long id) {
        return Result.ok(service.publish(id));
    }

    @PostMapping("/{id}/unpublish")
    public Result<FormDesignDto> unpublish(@PathVariable Long id) {
        return Result.ok(service.unpublish(id));
    }
}