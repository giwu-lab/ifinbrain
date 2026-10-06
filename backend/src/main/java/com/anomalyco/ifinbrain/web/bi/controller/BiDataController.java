package com.anomalyco.ifinbrain.web.bi.controller;

import com.anomalyco.ifinbrain.web.bi.dto.BiDataQueryReq;
import com.anomalyco.ifinbrain.web.bi.dto.BiDataResp;
import com.anomalyco.ifinbrain.web.bi.dto.BiQueryOptionDto;
import com.anomalyco.ifinbrain.web.bi.service.BiDataService;
import com.anomalyco.ifinbrain.web.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 大屏取数。权限位是 {@code bi:dataset:view}（在 BiDataService 入口校验）。 */
@RestController
@RequestMapping("/api/bi/data")
@RequiredArgsConstructor
public class BiDataController {

    private final BiDataService biDataService;

    @PostMapping("/query")
    public Result<BiDataResp> query(@Valid @RequestBody BiDataQueryReq req) {
        return Result.ok(biDataService.query(req));
    }

    /** 数据绑定面板的候选查询列表（只返回选查询必需的字段，不含 SQL） */
    @GetMapping("/queries")
    public Result<List<BiQueryOptionDto>> queries() {
        return Result.ok(biDataService.listQueryOptions());
    }
}
