package com.anomalyco.ifinbrain.web.datamgmt.controller;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.datamgmt.common.QueryExport;
import com.anomalyco.ifinbrain.web.datamgmt.dto.*;
import com.anomalyco.ifinbrain.web.datamgmt.service.QueryExecutor;
import com.anomalyco.ifinbrain.web.datamgmt.service.QueryPageService;
import com.anomalyco.ifinbrain.web.datamgmt.service.QueryService;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/datamgmt/queries")
@RequiredArgsConstructor
public class QueryController {

    private final QueryService queryService;
    private final QueryExecutor executor;
    private final QueryPageService queryPageService;
    private final PermissionChecker perm;

    @GetMapping
    public Result<List<QueryDto>> list() {
        return Result.ok(queryService.list());
    }

    @GetMapping("/{id}")
    public Result<QueryDto> detail(@PathVariable Long id) {
        return Result.ok(queryService.detail(id));
    }

    @PostMapping
    public Result<QueryDto> create(@Valid @RequestBody QueryCreateReq req) {
        return Result.ok(queryService.create(req, perm.currentUsername()));
    }

    @PutMapping("/{id}")
    public Result<QueryDto> update(@PathVariable Long id, @RequestBody QueryUpdateReq req) {
        return Result.ok(queryService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        queryService.delete(id);
        return Result.ok();
    }

    @PostMapping("/{id}/run")
    public Result<QueryResultDto> run(@PathVariable Long id, @RequestBody(required = false) QueryRunReq req) {
        return Result.ok(queryService.run(id, req, perm.currentUsername()));
    }

    // ===================== 发布 / 反发布 / 删除 =====================

    /** 一键发布：基于 query 创建 sys_menu 条目 */
    @PostMapping("/{id}/publish")
    public Result<Map<String, Object>> publish(@PathVariable Long id,
                                               @RequestBody(required = false) PublishQueryPageReq req) {
        if (req == null) req = new PublishQueryPageReq();
        req.setQueryId(id);
        return Result.ok(queryPageService.publish(req));
    }

    /** 编辑发布（修改 menuName / configJson） */
    @PutMapping("/{id}/page")
    public Result<Map<String, Object>> updatePublished(@PathVariable Long id,
                                                      @RequestBody UpdateQueryPageReq req) {
        Long menuId = queryService.detail(id).getPublishedMenuId();
        if (menuId == null) throw new BizException(404, "尚未发布");
        return Result.ok(queryPageService.update(menuId, req));
    }

    /** 软取消发布（status=0） */
    @PostMapping("/{id}/unpublish")
    public Result<Map<String, Object>> unpublish(@PathVariable Long id) {
        Long menuId = queryService.detail(id).getPublishedMenuId();
        if (menuId == null) throw new BizException(404, "尚未发布");
        return Result.ok(queryPageService.unpublish(menuId));
    }

    /** 恢复发布（status=1） */
    @PostMapping("/{id}/republish")
    public Result<Map<String, Object>> republish(@PathVariable Long id) {
        Long menuId = queryService.detail(id).getPublishedMenuId();
        if (menuId == null) throw new BizException(404, "尚未发布");
        return Result.ok(queryPageService.republish(menuId));
    }

    /** 硬删除：永久移除已发布的 menu 行 */
    @DeleteMapping("/{id}/page")
    public Result<Void> deletePublished(@PathVariable Long id) {
        Long menuId = queryService.detail(id).getPublishedMenuId();
        if (menuId == null) throw new BizException(404, "尚未发布");
        queryPageService.delete(menuId);
        return Result.ok();
    }

    /**
     * 流式 CSV 导出。直接写 OutputStream，不进内存。
     * GET + query string：浏览器直链 / fetch+blob 都可用。
     */
    @GetMapping(value = "/{id}/export.csv", produces = "text/csv; charset=utf-8")
    public void exportCsv(@PathVariable Long id,
                          @RequestParam(required = false) Map<String, String> params,
                          HttpServletResponse resp) throws Exception {
        QueryService.Prepared p = queryService.prepareForRun(id);
        String fname = sanitize(p.query().getName()) + "-" + LocalDate.now() + ".csv";
        resp.setContentType("text/csv; charset=utf-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + fname + "\"");
        resp.setCharacterEncoding("UTF-8");

        try (OutputStream os = resp.getOutputStream();
             BufferedWriter w = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
            // UTF-8 BOM — Excel 中文友好
            os.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            executor.iterateRows(p.ds(), p.plain(), p.query().getSqlTemplate(),
                    p.schema(), params == null ? Map.of() : params, 0L,
                    new QueryExecutor.RowHandler() {
                        @Override public void onHeaders(List<QueryResultDto.Column> columns) {
                            try {
                                QueryExport.writeCsvRow(w, columns.stream()
                                        .map(QueryResultDto.Column::getLabel)
                                        .toArray());
                            } catch (Exception e) { throw new RuntimeException(e); }
                        }
                        @Override public void onRow(List<Object> values) {
                            try {
                                QueryExport.writeCsvRow(w, values.toArray());
                            } catch (Exception e) { throw new RuntimeException(e); }
                        }
                    });
            w.flush();
        }
    }

    /**
     * 流式 XLSX 导出。SXSSFWorkbook(100) — 内存只保留最近 100 行。
     */
    @GetMapping(value = "/{id}/export.xlsx",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public void exportXlsx(@PathVariable Long id,
                           @RequestParam(required = false) Map<String, String> params,
                           HttpServletResponse resp) throws Exception {
        QueryService.Prepared p = queryService.prepareForRun(id);
        String fname = sanitize(p.query().getName()) + "-" + LocalDate.now() + ".xlsx";
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + fname + "\"");

        SXSSFWorkbook wb = new SXSSFWorkbook(100);
        try {
            SXSSFSheet sheet = wb.createSheet("data");
            var headerStyle = QueryExport.createHeaderStyle(wb);
            int[] rowIdx = {0};

            executor.iterateRows(p.ds(), p.plain(), p.query().getSqlTemplate(),
                    p.schema(), params == null ? Map.of() : params, 0L,
                    new QueryExecutor.RowHandler() {
                        @Override public void onHeaders(List<QueryResultDto.Column> columns) {
                            var r = sheet.createRow(rowIdx[0]++);
                            for (int i = 0; i < columns.size(); i++) {
                                var c = r.createCell(i);
                                c.setCellValue(columns.get(i).getLabel());
                                c.setCellStyle(headerStyle);
                            }
                        }
                        @Override public void onRow(List<Object> values) {
                            QueryExport.writeXlsxRow(sheet, rowIdx[0]++, values.toArray());
                        }
                    });

            try (OutputStream os = resp.getOutputStream()) {
                wb.write(os);
                wb.dispose();
            }
        } finally {
            wb.close();
        }
    }

    /** 文件名 sanitize：去掉路径分隔符与可疑字符 */
    private static String sanitize(String name) {
        if (name == null || name.isBlank()) return "query";
        return name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }
}