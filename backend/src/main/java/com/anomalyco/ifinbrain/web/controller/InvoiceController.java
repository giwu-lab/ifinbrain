package com.anomalyco.ifinbrain.web.controller;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.common.InvoiceExport;
import com.anomalyco.ifinbrain.web.common.PageReq;
import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.entity.InvoiceRecord;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.anomalyco.ifinbrain.web.service.InvoiceService;
import com.anomalyco.ifinbrain.web.service.InvoiceService.CommitReq;
import com.anomalyco.ifinbrain.web.service.InvoiceService.PendingInvoice;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final PermissionChecker perm;

    @GetMapping
    public Result<Map<String, Object>> list(PageReq req) {
        Page<InvoiceRecord> p = invoiceService.list(req);
        Map<String, Object> data = new HashMap<>();
        data.put("items", p.getContent());
        data.put("total", p.getTotalElements());
        data.put("page", req.getPage());
        data.put("size", req.getSize());
        return Result.ok(data);
    }

    /** 分组列表：同一发票号码合并为一行，formats 字段给出 PDF/XML/OFD 可用情况。
     *  方案 C 后：真正的服务器端分页，不再有 5000 上限。 */
    @GetMapping("/groups")
    public Result<Map<String, Object>> groups(PageReq req) {
        // limit 参数在方案 C 后忽略；保留 service 签名兼容
        Page<com.anomalyco.ifinbrain.web.dto.invoice.InvoiceGroupDto> p = invoiceService.listGrouped(req, 0);
        Map<String, Object> data = new HashMap<>();
        data.put("items", p.getContent());
        data.put("total", p.getTotalElements());
        data.put("page", req.getPage());
        data.put("size", req.getSize());
        return Result.ok(data);
    }

    @GetMapping("/{id}")
    public Result<InvoiceRecord> detail(@PathVariable Long id) {
        return Result.ok(invoiceService.detail(id));
    }

    /** 仅做识别 + 临时存储，不落库 */
    @PostMapping("/upload-and-recognize")
    public Result<List<PendingInvoice>> upload(@RequestParam("files") MultipartFile[] files) {
        Long uid = perm.currentUserId();
        return Result.ok(invoiceService.uploadAndRecognize(files, uid));
    }

    /** 用户点击「保存」后：重命名 + 落库 */
    @PostMapping("/commit")
    public Result<List<InvoiceRecord>> commit(@RequestBody CommitReq body) {
        if (body == null) body = new CommitReq();
        body.setUploaderId(perm.currentUserId());
        return Result.ok(invoiceService.commit(body));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody InvoiceRecord patch) {
        invoiceService.update(id, patch);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        invoiceService.delete(id);
        return Result.ok();
    }

    /**
     * 批量删除：对应发票分组列表「一行多格式」场景，一次 HTTP 调用完成整组（含存储文件）。
     * Body: { "ids": [1, 2, 3] }
     * 返回: { rowsDeleted, filesDeleted, filesMissing, skipped, missing[] }
     */
    @PostMapping("/batch-delete")
    public Result<Map<String, Object>> batchDelete(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("ids");
        List<Long> ids = new ArrayList<>();
        if (raw instanceof java.util.Collection<?> col) {
            for (Object o : col) {
                if (o == null) continue;
                try { ids.add(Long.parseLong(o.toString())); }
                catch (NumberFormatException ignored) {}
            }
        }
        return Result.ok(invoiceService.deleteBatch(ids));
    }

    /** 全库查重：返回按 (发票号, 文件格式) 分组的重复组。
     *  方案 C 后：SQL GROUP BY，无截断。 */
    @GetMapping("/duplicates")
    public Result<List<Map<String, Object>>> duplicates() {
        // limit 参数在方案 C 后忽略；保留 service 签名兼容
        return Result.ok(invoiceService.findDuplicates(0));
    }

    /**
     * 清理按 (发票号, 文件格式) 重复的记录 + 文件：
     * 每组保留 id 最小（最早入库）的一条，其余连同 storage 文件一并删除。
     * 需 invoice:delete 权限。
     */
    @PostMapping("/dedupe")
    public Result<Map<String, Object>> dedupe() {
        return Result.ok(invoiceService.dedupe());
    }

    /**
     * 查询传入的发票号集合中已存在于数据库的记录。
     * Body: { "invoiceNumbers": ["...", "..."] }
     */
    @PostMapping("/exists")
    public Result<List<InvoiceRecord>> exists(@RequestBody java.util.Map<String, Object> body) {
        Object raw = body == null ? null : body.get("invoiceNumbers");
        java.util.List<String> numbers = new java.util.ArrayList<>();
        if (raw instanceof java.util.Collection<?> col) {
            for (Object o : col) {
                if (o != null) numbers.add(o.toString());
            }
        }
        return Result.ok(invoiceService.findExistingByNumbers(numbers));
    }

    /** 导出 CSV：游标分批 + 直接写入 OutputStream，内存恒定。
     *  同步阶段直接写 response（不走 async），避免 Spring Security async 重复校验问题。
     *  前端 fetch().blob() + <a download> 即可下载。 */
    @GetMapping("/export/csv")
    public void exportCsv(PageReq req, HttpServletResponse response) throws java.io.IOException {
        perm.require("invoice:list");
        String filename = "invoices-" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".csv";
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition",
                ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString());
        invoiceService.streamExport(req, response.getOutputStream(), "csv");
    }

    /** 导出 XLSX：游标分批 + SXSSFWorkbook 流式（POI 100 行 buffer） + 直接写 response。 */
    @GetMapping("/export/xlsx")
    public void exportXlsx(PageReq req, HttpServletResponse response) throws java.io.IOException {
        perm.require("invoice:list");
        String filename = "invoices-" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".xlsx";
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition",
                ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString());
        invoiceService.streamExport(req, response.getOutputStream(), "xlsx");
    }
}