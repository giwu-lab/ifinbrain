package com.anomalyco.ifinbrain.web.datamgmt.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.datamgmt.dto.PublishQueryPageReq;
import com.anomalyco.ifinbrain.web.datamgmt.dto.UpdateQueryPageReq;
import com.anomalyco.ifinbrain.web.datamgmt.service.QueryPageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/datamgmt/query-pages")
@RequiredArgsConstructor
public class QueryPageController {

    private final QueryPageService service;

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(service.list());
    }

    @PostMapping
    public Result<Map<String, Object>> publish(@Valid @RequestBody PublishQueryPageReq req) {
        return Result.ok(service.publish(req));
    }

    @PutMapping("/{menuId}")
    public Result<Map<String, Object>> update(@PathVariable Long menuId,
                                             @RequestBody UpdateQueryPageReq req) {
        return Result.ok(service.update(menuId, req));
    }

    @PostMapping("/{menuId}/unpublish")
    public Result<Map<String, Object>> unpublish(@PathVariable Long menuId) {
        return Result.ok(service.unpublish(menuId));
    }

    @PostMapping("/{menuId}/republish")
    public Result<Map<String, Object>> republish(@PathVariable Long menuId) {
        return Result.ok(service.republish(menuId));
    }

    @DeleteMapping("/{menuId}")
    public Result<Void> delete(@PathVariable Long menuId) {
        service.delete(menuId);
        return Result.ok();
    }

    /** 解析 menuId → queryId，给生成的列表页用 */
    @GetMapping("/{menuId}/query-id")
    public Result<Long> resolveQueryId(@PathVariable Long menuId) {
        return Result.ok(service.resolveQueryId(menuId));
    }

    /** 完整菜单详情（含 configJson）—— 给生成的列表页用 */
    @GetMapping("/{menuId}")
    public Result<Map<String, Object>> detail(@PathVariable Long menuId) {
        return Result.ok(service.detail(menuId));
    }
}