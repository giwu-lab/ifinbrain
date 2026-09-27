package com.anomalyco.ifinbrain.web.design.portal.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.design.portal.dto.PortalBlockDto;
import com.anomalyco.ifinbrain.web.design.portal.service.PortalBlockService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class PortalBlockController {

    private final PortalBlockService service;

    /** 给前端编辑器用：列出所有启用中的内置块，作为左侧组件库 */
    @GetMapping("/api/design/portal/blocks")
    public Result<List<PortalBlockDto>> listEnabled() {
        return Result.ok(service.listEnabled());
    }
}