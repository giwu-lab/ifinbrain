package com.anomalyco.fapiao.web.controller;

import com.anomalyco.fapiao.web.common.BizException;
import com.anomalyco.fapiao.web.common.Result;
import com.anomalyco.fapiao.web.dto.i18n.I18nImportResult;
import com.anomalyco.fapiao.web.dto.i18n.I18nMessageDto;
import com.anomalyco.fapiao.web.dto.i18n.I18nUpsertRequest;
import com.anomalyco.fapiao.web.service.I18nService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system/i18n")
@RequiredArgsConstructor
public class I18nController {

    private final I18nService i18nService;

    /** 全量快照：{locale: nestedMap} —— 给前端一次性注入 vue-i18n */
    @GetMapping("/messages/all")
    public Result<Map<String, Map<String, Object>>> messagesAll() {
        return Result.ok(i18nService.listAllByLocale());
    }

    /** 单个命名空间下所有条目（含 i18n 翻译） */
    @GetMapping("/{namespace}")
    public Result<List<I18nMessageDto>> listByNamespace(@PathVariable String namespace) {
        return Result.ok(i18nService.listByNamespace(namespace));
    }

    /** 已存在的 namespace 列表 */
    @GetMapping("/namespaces")
    public Result<List<String>> namespaces() {
        return Result.ok(i18nService.listNamespaces());
    }

    /** 各 namespace 的条目数 */
    @GetMapping("/counts")
    public Result<Map<String, Long>> counts() {
        return Result.ok(i18nService.countByNamespace());
    }

    /** upsert 单条 */
    @PutMapping("/{namespace}/{code}")
    public Result<I18nMessageDto> upsert(@PathVariable String namespace,
                                         @PathVariable String code,
                                         @RequestBody I18nUpsertRequest body) {
        String v = body != null && body.getValueJson() != null ? body.getValueJson() : null;
        return Result.ok(i18nService.upsert(namespace, code, v));
    }

    @DeleteMapping("/{namespace}/{code}")
    public Result<Void> delete(@PathVariable String namespace, @PathVariable String code) {
        i18nService.delete(namespace, code);
        return Result.ok();
    }

    /** 重置命名空间：清掉所有该 namespace 的记录，前端会自动用 ts 文件作 fallback */
    @DeleteMapping("/{namespace}/reset")
    public Result<Void> resetNamespace(@PathVariable String namespace) {
        i18nService.resetNamespace(namespace);
        return Result.ok();
    }

    /** 导出全库翻译为 JSON 文件 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        String json = i18nService.exportAll();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String filename = "i18n-" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".json";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setContentDispositionFormData("attachment",
                URLEncoder.encode(filename, StandardCharsets.UTF_8));
        return new ResponseEntity<>(bytes, h, org.springframework.http.HttpStatus.OK);
    }

    /** 批量导入。mode=merge 存在则更新；mode=replace 先清空再插 */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<I18nImportResult> importFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "mode", required = false, defaultValue = "merge") String mode) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "文件为空");
        }
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new BizException(400, "文件超过 5MB");
        }
        return Result.ok(i18nService.importFile(file, mode));
    }

    /** 一键同步：把 classpath:i18n/defaults.json（前端 ts 编译出的默认值）写入 DB。
     *  merge 模式，已存在则覆盖为默认值。 */
    @PostMapping("/sync-defaults")
    public Result<I18nImportResult> syncDefaults() {
        return Result.ok(i18nService.syncFromDefaults());
    }
}