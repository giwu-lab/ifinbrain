package com.anomalyco.fapiao.web.controller;

import com.anomalyco.fapiao.web.common.BizException;
import com.anomalyco.fapiao.web.common.Result;
import com.anomalyco.fapiao.web.dto.dict.DictImportResult;
import com.anomalyco.fapiao.web.dto.dict.DictItemDto;
import com.anomalyco.fapiao.web.dto.dict.DictTypeDto;
import com.anomalyco.fapiao.web.service.DictService;
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
@RequestMapping("/api/system/dicts")
@RequiredArgsConstructor
public class DictController {

    private final DictService dictService;

    // ===================== 类型 =====================

    @GetMapping("/types")
    public Result<List<DictTypeDto>> listTypes() {
        return Result.ok(dictService.listAllTypes());
    }

    @PostMapping("/types")
    public Result<DictTypeDto> createType(@RequestBody DictTypeReq req) {
        return Result.ok(dictService.createType(req.code(), req.name(), req.remark(), req.status()));
    }

    @PutMapping("/types/{id}")
    public Result<DictTypeDto> updateType(@PathVariable Long id, @RequestBody DictTypeReq req) {
        return Result.ok(dictService.updateType(id, req.name(), req.remark(), req.status()));
    }

    @DeleteMapping("/types/{id}")
    public Result<Void> deleteType(@PathVariable Long id) {
        dictService.deleteType(id);
        return Result.ok();
    }

    // ===================== 条目 =====================

    @GetMapping("/types/{code}/items")
    public Result<List<DictItemDto>> listItems(@PathVariable String code) {
        return Result.ok(dictService.listItemsByTypeCode(code));
    }

    @PostMapping("/types/{id}/items")
    public Result<DictItemDto> createItem(@PathVariable Long id, @RequestBody DictItemReq req) {
        return Result.ok(dictService.createItem(id, req.code(), req.label(), req.value(),
                req.parentId(), req.sort(), req.status()));
    }

    @PutMapping("/items/{id}")
    public Result<DictItemDto> updateItem(@PathVariable Long id, @RequestBody DictItemReq req) {
        return Result.ok(dictService.updateItem(id, req.label(), req.value(),
                req.parentId(), req.sort(), req.status()));
    }

    @DeleteMapping("/items/{id}")
    public Result<Void> deleteItem(@PathVariable Long id) {
        dictService.deleteItem(id);
        return Result.ok();
    }

    // ===================== 全量缓存 =====================

    @GetMapping("/cache")
    public Result<Map<String, List<DictItemDto>>> cache() {
        return Result.ok(dictService.cache());
    }

    // ===================== 导出 / 导入 =====================

    /** 导出全库字典为 JSON 文件下载 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        String json = dictService.exportAll();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String filename = "dicts-" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".json";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setContentDispositionFormData("attachment",
                URLEncoder.encode(filename, StandardCharsets.UTF_8));
        return new ResponseEntity<>(bytes, h, org.springframework.http.HttpStatus.OK);
    }

    /** 批量导入：dryRun=true 只解析不写 */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<DictImportResult> importFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "dryRun", required = false, defaultValue = "false") boolean dryRun) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "文件为空");
        }
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new BizException(400, "文件超过 5MB");
        }
        return Result.ok(dictService.importFile(file, dryRun));
    }

    // ===================== 请求体 =====================

    public record DictTypeReq(String code, String name, String remark, Short status) {}
    public record DictItemReq(String code, String label, String value,
                              Long parentId, Integer sort, Short status) {}
}