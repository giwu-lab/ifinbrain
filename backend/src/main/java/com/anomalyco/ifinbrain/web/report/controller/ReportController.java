package com.anomalyco.ifinbrain.web.report.controller;

import com.anomalyco.ifinbrain.web.common.PageReq;
import com.anomalyco.ifinbrain.web.common.PageResp;
import com.anomalyco.ifinbrain.web.common.PublishMenuReq;
import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.report.dto.*;
import com.anomalyco.ifinbrain.web.report.service.ReportService;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    /** 设计器读报表：要求实体级 design 权限（光有 view 不够 —— 能看 ≠ 能改） */
    @GetMapping("/{id}/design")
    public Result<ReportDto> detailForDesign(@PathVariable Long id) {
        return Result.ok(reportService.detailForDesign(id));
    }

    /**
     * 这张报表 4 个实体级权限码（view/design/publish/delete）当前绑定的角色。
     * 返回 {@code {verb: [roleId]}}，供前端授权对话框预填勾选状态。
     */
    @GetMapping("/{id}/permissions")
    public Result<Map<String, List<Long>>> permissions(@PathVariable Long id) {
        return Result.ok(reportService.getPermissions(id));
    }

    /**
     * Body: {@code { view:[roleIds], design:[...], publish:[...], delete:[...] }}
     * 空列表 = 收回全部授权（admin 角色始终保留）。
     */
    @PutMapping("/{id}/permissions")
    public Result<Void> updatePermissions(@PathVariable Long id,
                                           @RequestBody Map<String, List<Long>> grants) {
        reportService.updatePermissions(id, grants);
        return Result.ok();
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

    /**
     * 可选父目录列表（发布对话框用）。
     *
     * <p>路径用字面量 {@code /publish-dirs} 而不是 {@code /{id}/...}：
     * 它列的是目录不是报表，没有 id。
     */
    @GetMapping("/publish-dirs")
    public Result<List<Map<String, Object>>> publishDirs() {
        return Result.ok(reportService.publishDirs());
    }

    /**
     * 发布 = 在菜单树里建入口（挂在指定目录下），不只是一个状态位。
     *
     * <p>body 可空：全部字段都有默认值，不填就挂到 /bi 目录、用报表名做菜单名。
     */
    @PostMapping("/{id}/publish")
    public Result<ReportDto> publish(@PathVariable Long id,
                                     @RequestBody(required = false) PublishMenuReq req) {
        return Result.ok(reportService.publish(id, req));
    }

    /** 取消发布：退回草稿 + 菜单软下线（status=0，可恢复） */
    @PostMapping("/{id}/unpublish")
    public Result<ReportDto> unpublish(@PathVariable Long id) {
        return Result.ok(reportService.unpublish(id));
    }

    // ===================== 导出 =====================

    /**
     * 流式 CSV 导出。直接写 OutputStream，不在堆里拼整个文件。
     *
     * <p>GET + query string：与发票列表的导出保持一致，浏览器直链和 fetch+blob 都可用。
     * 导出的是**报表当前查询头条件下的完整结果**（受 maxRows 护栏约束），
     * 不是当前屏幕滚动到的那几页。
     */
    @GetMapping(value = "/{id}/export.csv", produces = "text/csv; charset=utf-8")
    public void exportCsv(@PathVariable Long id,
                          @RequestParam(required = false) Map<String, String> params,
                          HttpServletResponse resp) throws Exception {
        reportService.exportCsv(id, params == null ? Map.of() : params, resp);
    }

    /** 流式 XLSX 导出：SXSSFWorkbook(100)，内存只保留最近 100 行 */
    @GetMapping(value = "/{id}/export.xlsx",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public void exportXlsx(@PathVariable Long id,
                           @RequestParam(required = false) Map<String, String> params,
                           HttpServletResponse resp) throws Exception {
        reportService.exportXlsx(id, params == null ? Map.of() : params, resp);
    }
}
