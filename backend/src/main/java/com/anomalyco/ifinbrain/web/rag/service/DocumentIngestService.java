package com.anomalyco.ifinbrain.web.rag.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.config.RagProperties;
import com.anomalyco.ifinbrain.web.documents.entity.ArchiveLibrary;
import com.anomalyco.ifinbrain.web.documents.entity.DocumentRecord;
import com.anomalyco.ifinbrain.web.documents.repository.ArchiveLibraryRepository;
import com.anomalyco.ifinbrain.web.documents.repository.DocumentRecordRepository;
import com.anomalyco.ifinbrain.web.rag.repository.RagChunkRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.anomalyco.ifinbrain.web.storage.StorageSelector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 档案入库：读取文件 → 抽取文本 → 分块 → 批量 embedding → 写入向量表。
 *
 * <p>写库前会先 DELETE 该 document_id 的旧分块再 INSERT（{@code replaceChunks}），
 * 因此重跑是幂等的，不会因为重复执行产生两份分块。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ifinbrain.rag", name = "enabled", havingValue = "true")
public class DocumentIngestService {

    /** status = 1 READY。只有真正可读的档案才值得向量化。 */
    private static final short STATUS_READY = 1;

    private final TextExtractor extractor;
    private final RagChunkRepository chunks;
    private final DocumentRecordRepository docRepo;
    private final ArchiveLibraryRepository libraryRepo;
    private final StorageSelector storage;
    private final PermissionChecker perm;
    private final EmbeddingModel embeddingModel;
    private final RagProperties props;

    public record IngestResult(Long documentId, String filename, boolean indexed,
                               int chunkCount, String note) {
    }

    /** 单个档案入库。需要该档案所属档案库的 update 权限。 */
    @Transactional
    public IngestResult ingest(Long documentId) {
        DocumentRecord doc = docRepo.findById(documentId)
                .orElseThrow(() -> new BizException(404, "档案不存在"));
        ArchiveLibrary lib = libraryRepo.findById(doc.getCategoryId())
                .orElseThrow(() -> new BizException(404, "档案库不存在"));
        perm.require("documents:" + lib.getSlug() + ":update");

        return index(doc, lib);
    }

    private IngestResult index(DocumentRecord doc, ArchiveLibrary lib) {
        String filename = doc.getOriginalFilename();

        if (doc.getStatus() == null || doc.getStatus() != STATUS_READY) {
            // MISSING / PENDING_* 的档案内容不可信，清掉可能存在的旧分块后返回
            chunks.deleteByDocumentId(doc.getId());
            return new IngestResult(doc.getId(), filename, false, 0,
                    "档案状态不是 READY（status=" + doc.getStatus() + "），已清除旧分块");
        }

        byte[] bytes;
        try {
            bytes = storage.current().readAllBytes(doc.getStorageKey());
        } catch (Exception ex) {
            log.warn("[rag] read failed doc={} key={}: {}", doc.getId(), doc.getStorageKey(), ex.toString());
            return new IngestResult(doc.getId(), filename, false, 0, "读取文件内容失败");
        }

        TextExtractor.ExtractResult er = extractor.extract(filename, doc.getMimeType(), bytes);
        if (!er.supported()) {
            chunks.deleteByDocumentId(doc.getId());
            return new IngestResult(doc.getId(), filename, false, 0, er.note());
        }
        if (er.isEmpty()) {
            chunks.deleteByDocumentId(doc.getId());
            return new IngestResult(doc.getId(), filename, false, 0, "未抽取到文字内容，已清除旧分块");
        }

        List<String> pieces = TextChunker.split(er.text(), props.getChunkSize(), props.getChunkOverlap());
        if (pieces.isEmpty()) {
            chunks.deleteByDocumentId(doc.getId());
            return new IngestResult(doc.getId(), filename, false, 0, "切分后无有效分块");
        }

        List<float[]> vectors = embedAll(pieces);
        chunks.replaceChunks(doc.getId(), lib.getId(), filename,
                pieces, vectors, props.getDimensions());
        return new IngestResult(doc.getId(), filename, true, pieces.size(), null);
    }

    /**
     * 批量 embedding，按 {@code embedBatchSize} 切批。
     * 单批失败不吞：整个文档的向量必须来自同一模型同一版本，缺一批就是脏数据。
     */
    private List<float[]> embedAll(List<String> pieces) {
        List<float[]> out = new ArrayList<>(pieces.size());
        int batch = Math.max(1, props.getEmbedBatchSize());
        for (int i = 0; i < pieces.size(); i += batch) {
            List<String> slice = pieces.subList(i, Math.min(i + batch, pieces.size()));
            out.addAll(embeddingModel.embed(slice));
        }
        return out;
    }

    /**
     * 重建整个档案库的索引。需要该档案库的 update 权限。
     * 单个文件失败只记录不中断 —— 一个坏文件不该让整库重建前功尽弃。
     */
    @Transactional
    public IngestSummary reindexLibrary(Long libraryId) {
        ArchiveLibrary lib = libraryRepo.findById(libraryId)
                .orElseThrow(() -> new BizException(404, "档案库不存在"));
        perm.require("documents:" + lib.getSlug() + ":update");

        List<DocumentRecord> docs = docRepo.findByCategoryIdAndStatusOrderByCreatedAtDesc(
                libraryId, STATUS_READY, org.springframework.data.domain.Pageable.unpaged()).getContent();

        int ok = 0, skipped = 0, failed = 0;
        for (DocumentRecord d : docs) {
            try {
                IngestResult r = index(d, lib);
                if (r.indexed()) ok++;
                else { skipped++; log.info("[rag] skip doc={} {}: {}", d.getId(), d.getOriginalFilename(), r.note()); }
            } catch (Exception ex) {
                failed++;
                log.warn("[rag] ingest failed doc={} {}: {}", d.getId(), d.getOriginalFilename(), ex.toString());
            }
        }
        log.info("[rag] reindex library={} slug={} total={} ok={} skipped={} failed={}",
                libraryId, lib.getSlug(), docs.size(), ok, skipped, failed);
        return new IngestSummary(libraryId, lib.getSlug(), docs.size(), ok, skipped, failed);
    }

    public record IngestSummary(Long libraryId, String slug, int total, int indexed, int skipped, int failed) {
    }
}
