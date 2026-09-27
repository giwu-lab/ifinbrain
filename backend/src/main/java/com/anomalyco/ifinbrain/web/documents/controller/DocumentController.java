package com.anomalyco.ifinbrain.web.documents.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.documents.dto.CreateFolderReq;
import com.anomalyco.ifinbrain.web.documents.dto.DocumentDto;
import com.anomalyco.ifinbrain.web.documents.dto.DocumentRenameReq;
import com.anomalyco.ifinbrain.web.documents.service.DocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService service;

    @GetMapping
    public Result<Page<DocumentDto>> list(@RequestParam Long categoryId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return Result.ok(service.list(categoryId, page, size));
    }

    @GetMapping("/{id}")
    public Result<DocumentDto> detail(@PathVariable Long id) {
        return Result.ok(service.detail(id));
    }

    @PostMapping("/upload")
    public Result<DocumentDto> upload(@RequestParam Long categoryId,
                                     @RequestParam(value = "path", required = false, defaultValue = "") String path,
                                     @RequestParam("file") MultipartFile file) {
        return Result.ok(service.upload(categoryId, file, path));
    }

    /** 在档案库下创建子文件夹路径（kebab-case 单段或多段，用 / 分隔）。path 为父目录（空 = 档案库根）。 */
    @PostMapping("/folders")
    public Result<Map<String, String>> createFolder(@Valid @RequestBody CreateFolderReq req) {
        String path = service.createFolder(req.getCategoryId(), req.getPath());
        return Result.ok(Map.of("path", path));
    }

    @PutMapping("/{id}")
    public Result<DocumentDto> rename(@PathVariable Long id,
                                      @Valid @RequestBody DocumentRenameReq req) {
        return Result.ok(service.rename(id, req.getName()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
