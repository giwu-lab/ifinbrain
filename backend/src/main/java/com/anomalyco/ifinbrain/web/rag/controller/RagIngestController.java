package com.anomalyco.ifinbrain.web.rag.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.rag.repository.RagChunkRepository;
import com.anomalyco.ifinbrain.web.rag.service.DocumentIngestService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 档案向量化索引的管理入口。权限校验在各 service 内按档案库 slug 做。 */
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ifinbrain.rag", name = "enabled", havingValue = "true")
public class RagIngestController {

    private final DocumentIngestService ingestService;
    private final RagChunkRepository chunks;

    /** 单个档案重新入库。 */
    @PostMapping("/documents/{id}/index")
    public Result<DocumentIngestService.IngestResult> indexDocument(@PathVariable Long id) {
        return Result.ok(ingestService.ingest(id));
    }

    /** 整个档案库重建索引。单文件失败不阻断整库。 */
    @PostMapping("/libraries/{id}/reindex")
    public Result<DocumentIngestService.IngestSummary> reindexLibrary(@PathVariable Long id) {
        return Result.ok(ingestService.reindexLibrary(id));
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.ok(Map.of("totalChunks", chunks.countAll()));
    }
}
