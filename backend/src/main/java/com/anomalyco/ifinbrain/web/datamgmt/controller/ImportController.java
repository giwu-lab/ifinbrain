package com.anomalyco.ifinbrain.web.datamgmt.controller;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.datamgmt.dto.*;
import com.anomalyco.ifinbrain.web.datamgmt.service.*;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 数据导入：Excel / CSV → 外部数据源表。
 *
 * <p>权限位与「数据源」模块完全正交（详见 V61 迁移注释）：
 * 谁能连上外部库、谁能看表结构、谁能往里写数据，是三个独立的问题。
 *
 * <p>四个步骤对应四个接口，**只有最后一个写库**：
 * <pre>
 *   POST /preview   上传 + 解析        data:import:view
 *   POST /validate  字段映射 + 预检    data:import:view
 *   POST /commit    真正写入          data:import:execute   ← 唯一的写操作
 * </pre>
 * 前两步只读，用户可以反复调；commit 之后会话立即销毁，避免误点二次写入。
 */
@Slf4j
@RestController
@RequestMapping("/api/datamgmt/imports")
@RequiredArgsConstructor
public class ImportController {

    private final ImportFileReader fileReader;
    private final ImportSessionService sessionService;
    private final ImportExecutorService executor;
    private final ImportTemplateService templateService;
    private final ImportLogService logService;
    private final PermissionChecker perm;

    @Value("${ifinbrain.import.max-file-size:20MB}")
    private DataSize maxFileSize;

    @Value("${ifinbrain.import.max-rows:50000}")
    private int maxRows;

    @Value("${ifinbrain.import.preview-rows:20}")
    private int previewRows;

    /**
     * 第 1~2 步：选表 + 上传解析。
     *
     * <p>这里顺带做一次目标表校验（权限 / 白名单 / 结构），所以 preview 返回的
     * {@code columns} 就是「执行时会写入的那张表当时的真实列」——
     * 用户在映射界面上看到的选项，不是一份可能过期的快照。
     */
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ImportPreviewDto> preview(@RequestParam Long datasourceId,
                                            @RequestParam String schema,
                                            @RequestParam String table,
                                            @RequestParam(required = false) String sheet,
                                            @RequestParam(defaultValue = "true") boolean hasHeader,
                                            @RequestParam(defaultValue = "auto") String encoding,
                                            @RequestParam("file") MultipartFile file) {

        perm.require("data:import:view");

        if (file.getSize() > maxFileSize.toBytes()) {
            throw new BizException(400, "文件过大（" + (file.getSize() / 1024 / 1024) + "MB），"
                    + "单文件上限 " + maxFileSize.toMegabytes() + "MB，请拆分后再导入");
        }

        var parsed = fileReader.read(file, sheet, hasHeader, encoding, maxRows);

        // 列数闸门放在建会话之前：解析完就判掉，不留一个用不上的空壳 session 占容量
        if (parsed.headers().size() > 200) {
            throw new BizException(400, "列数过多（" + parsed.headers().size() + " 列），请确认是否选错了工作表");
        }

        // 顺带校验目标表（权限 / 白名单 / 结构），把真实列一起回给前端
        var targetColumns = executor.resolveTargetColumns(
                datasourceId, schema, table, "data:import:view");

        var session = sessionService.create(datasourceId, schema, table, file, parsed, hasHeader);

        List<List<String>> sample = parsed.rows().stream()
                .limit(Math.max(1, previewRows))
                .toList();

        return Result.ok(new ImportPreviewDto(
                session.getId(),
                session.getFileName(),
                session.getFileSize(),
                parsed.kind().name(),
                parsed.sheetNames(),
                parsed.usedSheet(),
                parsed.detectedEncoding(),
                parsed.headers(),
                sample,
                parsed.totalRows(),
                parsed.truncated(),
                targetColumns));
    }

    /** 第 3 步：字段映射 + 预检（不写库） */
    @PostMapping("/validate")
    public Result<ImportValidateDto> validate(@Valid @RequestBody ImportMappingReq req) {
        return Result.ok(executor.validate(req));
    }

    /** 第 4 步：真正写库（唯一的写操作，需要 data:import:execute） */
    @PostMapping("/commit")
    public Result<ImportCommitResultDto> commit(@Valid @RequestBody ImportMappingReq req) {
        return Result.ok(executor.commit(req));
    }

    /** 导入历史（可按数据源过滤） */
    @GetMapping("/history")
    public Result<Map<String, Object>> history(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int size,
                                              @RequestParam(required = false) Long datasourceId) {
        return Result.ok(logService.list(datasourceId, page, size));
    }

    /** 导入详情（含失败明细） */
    @GetMapping("/{id}")
    public Result<ImportLogDto> detail(@PathVariable Long id) {
        return Result.ok(logService.detail(id));
    }

    /** 删除导入日志（审计痕迹，默认只给 admin） */
    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        logService.delete(id);
        return Result.ok();
    }

    /**
     * 下载导入模板：按目标表的真实列结构生成 xlsx（列名 + 「填写说明」sheet）。
     * 权限与 preview 同级 —— 拿模板不需要写库能力。
     */
    @GetMapping("/template")
    public void template(@RequestParam Long datasourceId,
                         @RequestParam String schema,
                         @RequestParam String table,
                         HttpServletResponse resp) {
        byte[] bytes = templateService.build(datasourceId, schema, table);
        String fname = "import-template-" + table + ".xlsx";

        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        setContentDisposition(resp, fname);
        try (var os = resp.getOutputStream()) {
            os.write(bytes);
            os.flush();
        } catch (Exception e) {
            log.warn("[import] template download failed: {}", e.getMessage());
        }
    }

    /** 用户改了目标表 / 换文件时主动丢弃会话（不必等 TTL） */
    @DeleteMapping("/session/{sessionId}")
    public Result<Void> discardSession(@PathVariable String sessionId) {
        perm.require("data:import:view");
        sessionService.discard(sessionId);
        return Result.ok();
    }

    /**
     * Content-Disposition：ASCII fallback + RFC 6266 filename*。
     * 旧客户端只看 ASCII，现代浏览器优先 filename*（中文表名必须走后者）。
     */
    private static void setContentDisposition(HttpServletResponse resp, String fname) {
        String ascii = fname.replaceAll("[^\\x20-\\x7E]", "_");
        if (ascii.isBlank()) ascii = "import-template.xlsx";
        String encoded = URLEncoder.encode(fname, StandardCharsets.UTF_8);
        resp.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded);
    }
}