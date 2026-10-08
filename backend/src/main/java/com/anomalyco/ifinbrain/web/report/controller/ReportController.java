package com.anomalyco.ifinbrain.web.report.controller;

import com.anomalyco.ifinbrain.web.common.PageReq;
import com.anomalyco.ifinbrain.web.common.PageResp;
import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.report.dto.*;
import com.anomalyco.ifinbrain.web.report.service.ReportService;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 报表 REST。
 *
 * <p>取数走 {@code POST /{id}/run} 而不是 GET：请求体带查询头参数，
 * 放 URL 会让长参数串进 access log 和浏览器历史。
 */
@RestController
@RequestMapping("/api/bi/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final PermissionChecker perm;

    /** 列表页：真分页，支持 keyword（名称 / 编码 / 描述）与 sortBy+sortDir */
    @GetMapping
    public Result<PageResp<ReportDto>> list(PageReq req) {
        return Result.ok(reportService.page(req));
    }

    @GetMapping("/{id}")
    public Result<ReportDto> detail(@PathVariable Long id) {
        return Result.ok(reportService.detail(id));
    }

    /** 设计器拉可绑定的查询列表（不含 SQL 文本） */
    @GetMapping("/query-options")
    public Result<List<QueryOption>> queryOptions() {
        return Result.ok(reportService.listQueryOptions());
    }

    /**
     * 设计器「试跑」：按 queryId 取首屏看列，**不落库**。
     *
     * <p>POST + body：查询头参数可能不少，放 URL 会让长参数串进 access log 和浏览器历史。
     * 路径刻意用字面量 {@code /preview} 而不是 {@code /{id}/preview} —— 试跑本来就不需要报表 id，
     * 借用 id 会把「新建报表先看数据」这条路重新绑死在「必须先存」上。
     */
    @PostMapping("/preview")
    public Result<ReportRunResp> preview(@Valid @RequestBody ReportPreviewReq req) {
        return Result.ok(reportService.preview(req));
    }

    /** 虚拟滚动分页取数 */
    @PostMapping("/{id}/run")
    public Result<ReportRunResp> run(@PathVariable Long id,
                                     @Valid @RequestBody ReportRunReq req) {
        return Result.ok(reportService.run(id, req));
    }

    @PostMapping
    public Result<ReportDto> create(@Valid @RequestBody ReportCreateReq req) {
        return Result.ok(reportService.create(req, perm.currentUsername()));
    }

    @PutMapping("/{id}")
    public Result<ReportDto> update(@PathVariable Long id, @RequestBody ReportUpdateReq req) {
        return Result.ok(reportService.update(id, req));
    }

    @PostMapping("/{id}/copy")
    public Result<ReportDto> copy(@PathVariable Long id) {
        return Result.ok(reportService.copy(id, perm.currentUsername()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        reportService.delete(id);
        return Result.ok();
    }

    @PostMapping("/{id}/publish")
    public Result<ReportDto> publish(@PathVariable Long id) {
        return Result.ok(reportService.publish(id));
    }

    @PostMapping("/{id}/unpublish")
    public Result<ReportDto> unpublish(@PathVariable Long id) {
        return Result.ok(reportService.unpublish(id));
    }
}
