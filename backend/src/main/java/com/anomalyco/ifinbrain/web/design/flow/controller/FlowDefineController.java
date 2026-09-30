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
}