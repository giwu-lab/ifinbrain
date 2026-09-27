package com.anomalyco.ifinbrain.web.documents.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.documents.dto.DocumentBrowseDto;
import com.anomalyco.ifinbrain.web.documents.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 浏览档案库当前路径下的内容（子文件夹 + 文件）。
 * 路径独立成 controller 避免与 /api/documents/{id} 路由冲突。
 */
@RestController
@RequestMapping("/api/document-browse")
@RequiredArgsConstructor
public class DocumentBrowseController {

    private final DocumentService service;

    /** path 为空表示档案库根；多级如 "demo/sub1"。 */
    @GetMapping
    public Result<DocumentBrowseDto> browse(@RequestParam Long categoryId,
                                            @RequestParam(required = false, defaultValue = "") String path) {
        return Result.ok(service.browse(categoryId, path));
    }
}