package com.anomalyco.ifinbrain.web.documents.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.documents.dto.DocumentCategoryCreateReq;
import com.anomalyco.ifinbrain.web.documents.dto.DocumentCategoryDto;
import com.anomalyco.ifinbrain.web.documents.dto.DocumentCategoryUpdateReq;
import com.anomalyco.ifinbrain.web.documents.service.DocumentCategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/document-categories")
@RequiredArgsConstructor
public class DocumentCategoryController {

    private final DocumentCategoryService service;

    @GetMapping
    public Result<List<DocumentCategoryDto>> list() {
        return Result.ok(service.list());
    }

    @GetMapping("/{id}")
    public Result<DocumentCategoryDto> detail(@PathVariable Long id) {
        return Result.ok(service.detail(id));
    }

    @PostMapping
    public Result<DocumentCategoryDto> create(@Valid @RequestBody DocumentCategoryCreateReq req) {
        return Result.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public Result<DocumentCategoryDto> update(@PathVariable Long id,
                                               @Valid @RequestBody DocumentCategoryUpdateReq req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
