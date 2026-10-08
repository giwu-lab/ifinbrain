package com.anomalyco.ifinbrain.web.rag.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * rag_document_chunk 的原生 SQL 访问。
 *
 * <p>刻意不用 Spring AI 的 PgVectorStore，原因有二：
 * <ol>
 *   <li><b>权限</b>：PgVectorStore 的过滤走 metadata(jsonb)，过滤发生在应用层 ——
 *       检索结果先全量返回、再由代码判断丢弃。一旦哪次判断写漏，就是实打实的越权。
 *       这里把 {@code library_id = ANY(...)} 写进检索 SQL，无权档案的分块根本不会离开数据库。</li>
 *   <li><b>schema 归属</b>：项目约定全表走 Flyway。PgVectorStore 的 initialize-schema
 *       即使关掉，它仍要求表结构与它的默认 DDL 一致，反过来限制了我们的表设计。</li>
 * </ol>
 *
 * <p>条件装配：RAG 关闭时不注册。这里的 SQL 用 PG 方言（{@code vector} 类型、
 * {@code <=>} 距离算子），依赖装了 pgvector 扩展的库。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ifinbrain.rag", name = "enabled", havingValue = "true")
public class RagChunkRepository {

    private final JdbcTemplate jdbc;

    /** 检索命中的一条分块，附带与查询向量的余弦距离（越小越相似）。 */
    public record ScoredChunk(
            Long id,
            Long documentId,
            Long libraryId,
            int chunkIndex,
            String content,
            String sourceFilename,
            double distance) {
    }

    /**
     * 向量检索。{@code libraryIds} 是当前用户有权访问的档案库集合 —— 空集合直接返回空，
     * 绝不能退化成"不加过滤 = 全库可搜"。
     */
    public List<ScoredChunk> search(float[] queryVector, Collection<Long> libraryIds, int limit, double maxDistance) {
        if (libraryIds == null || libraryIds.isEmpty() || limit <= 0) {
            return List.of();
        }
        String sql = """
                SELECT c.id, c.document_id, c.library_id, c.chunk_index, c.content,
                       c.source_filename,
                       c.embedding <=> CAST(? AS vector) AS distance
                  FROM rag_document_chunk c
                 WHERE c.library_id = ANY(CAST(? AS bigint[]))
                 ORDER BY c.embedding <=> CAST(? AS vector)
                 LIMIT ?
                """;
        String vec = toVectorLiteral(queryVector);
        Long[] ids = libraryIds.toArray(new Long[0]);
        return jdbc.query(sql,
                (rs, i) -> new ScoredChunk(
                        rs.getLong("id"),
                        rs.getLong("document_id"),
                        rs.getLong("library_id"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        rs.getString("source_filename"),
                        rs.getDouble("distance")),
                vec, ids, vec, limit)
                .stream()
                .filter(c -> c.distance() <= maxDistance)
                .toList();
    }

    /**
     * 覆盖式写入某文档的全部分块：先删后插。重跑入库不会产生重复分块
     * （表上还有 UNIQUE(document_id, chunk_index) 兜底）。
     *
     * @param embeddings 与 chunks 一一对应，长度必须相同且等于 {@code dimensions}
     */
    public void replaceChunks(Long documentId, Long libraryId, String sourceFilename,
                              List<String> chunks, List<float[]> embeddings, int dimensions) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException(
                    "chunks(" + chunks.size() + ") 与 embeddings(" + embeddings.size() + ") 数量不一致");
        }
        jdbc.update("DELETE FROM rag_document_chunk WHERE document_id = ?", documentId);
        if (chunks.isEmpty()) {
            return;
        }

        List<Object[]> batch = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            float[] e = embeddings.get(i);
            if (e.length != dimensions) {
                // 不静默截断：维度对不上说明换了 embedding 模型或配置写错，
                // 直接失败比写进一堆查不出来的向量强。
                throw new IllegalStateException(
                        "embedding 维度不匹配：实际 " + e.length + "，配置 " + dimensions
                                + "。若更换了 embedding 模型，需同步修改 RagProperties.dimensions "
                                + "与 V45 迁移里的 vector(N) 定义。");
            }
            batch.add(new Object[]{
                    documentId, libraryId, i, chunks.get(i), estimateTokens(chunks.get(i)),
                    toVectorLiteral(e), sourceFilename});
        }
        jdbc.batchUpdate("""
                INSERT INTO rag_document_chunk
                       (document_id, library_id, chunk_index, content, token_count, embedding, source_filename)
                VALUES (?, ?, ?, ?, ?, CAST(? AS vector), ?)
                """, batch);
        log.info("[rag] document {} (library {}) indexed, {} chunks", documentId, libraryId, chunks.size());
    }

    /** 文档被删除 / 重命名时清分块。外键 ON DELETE CASCADE 是第二道保险。 */
    public void deleteByDocumentId(Long documentId) {
        jdbc.update("DELETE FROM rag_document_chunk WHERE document_id = ?", documentId);
    }

    /**
     * 档案重命名后同步分块的来源文件名，让引用溯源显示用户在列表里看到的新名字。
     *
     * <p>只改 provenance 字段，<b>不碰 content / embedding</b>：向量与文件名无关，
     * 重命名后召回质量不变，没必要重新 embedding（那是几十秒的外部 API 调用）。
     *
     * <p>也不影响 {@link #search} 的召回 —— 检索只在 WHERE 里按 library_id 过滤，
     * 这个字段仅在命中之后作为 Citation.filename 读出。
     *
     * <p>走 {@code idx_rag_chunk_document}，单文档更新是索引定位而非全表扫。
     *
     * @return 受影响分块数；0 表示该档案尚未入库（没建索引，属正常情况）
     */
    public int updateSourceFilename(Long documentId, String sourceFilename) {
        if (sourceFilename == null || sourceFilename.isBlank()) {
            return 0;
        }
        return jdbc.update("UPDATE rag_document_chunk SET source_filename = ? WHERE document_id = ?",
                sourceFilename, documentId);
    }

    public long countByDocumentId(Long documentId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM rag_document_chunk WHERE document_id = ?",
                Long.class, documentId);
        return n == null ? 0L : n;
    }

    public long countAll() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM rag_document_chunk", Long.class);
        return n == null ? 0L : n;
    }

    /**
     * float[] → pgvector 字面量 {@code [0.1,0.2]}。
     * 用 Locale.ROOT 固定小数点，否则在中文 Windows 上会输出逗号分隔符，
     * PG 解析时报 "invalid input syntax for type vector"。
     */
    static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 9 + 2);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.ROOT, "%.7f", v[i]));
        }
        return sb.append(']').toString();
    }

    /** 中文按字 ≈ 1 token，英文按 4 字符 ≈ 1 token 的粗估。仅用于观测分块粒度。 */
    private static int estimateTokens(String s) {
        long cjk = s.codePoints().filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN).count();
        return (int) (cjk + (s.length() - cjk) / 4 + 1);
    }
}
