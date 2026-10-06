-- V45: 档案库 RAG 向量分块表（pgvector）
--
-- 目的：为 documents 模块提供语义检索（档案库问答）。链路是
--   文件入库 → 文本抽取 → 分块 → bge-m3 embedding → 本表 → 向量检索 → 拼 prompt 生成。
--
-- 设计要点：
--  - schema 归 Flyway 管。Spring AI 侧不开自动建表（initialize-schema: false），
--    避免运行期改 schema —— 遵循 AGENTS.md §4.1「全表走 Flyway」。
--  - library_id / document_id 冗余存储，且都建外键。权限过滤放在 SQL 层用
--    `library_id = ANY(:allowedIds)` 完成：无权档案的分块根本不会被返回，
--    而不是检索回来再在应用层丢弃（后者一旦漏判就是越权）。
--  - embedding 固定 1024 维 = bge-m3 的输出维度。换模型必须同步改这一行，
--    否则插入时维度不匹配直接报错，不会静默写坏数据。
--  - HNSW + cosine_ops：bge-m3 官方推荐 cosine 相似度；pgvector 0.5+ 起
--    HNSW 为 PG 内核原生实现，无需 pgvector 的 hnsw 扩展。
--  - UNIQUE(document_id, chunk_index) 让重复入库幂等：重跑 embedding 任务
--    不会产生重复分块。

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE rag_document_chunk (
    id              BIGSERIAL       PRIMARY KEY,
    document_id     BIGINT          NOT NULL REFERENCES sys_document(id)           ON DELETE CASCADE,
    library_id      BIGINT          NOT NULL REFERENCES sys_document_category(id) ON DELETE CASCADE,
    -- 该分块在所属文档内的序号（从 0 开始）
    chunk_index     INTEGER         NOT NULL,
    content         TEXT            NOT NULL,
    -- 估算 token 数，仅用于观测分块粒度是否合理，不参与检索
    token_count     INTEGER,
    -- 1024 维 = bge-m3 输出维度。换模型必须同步改这里
    embedding       vector(1024)   NOT NULL,
    -- 冗余存原始文件名：检索结果要直接可读，避免每次都 join sys_document
    source_filename VARCHAR(255),
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_rag_chunk_doc_idx UNIQUE (document_id, chunk_index)
);

-- 按档案库过滤是检索热路径（RAG 每次查询都要带 library_id 条件）
CREATE INDEX idx_rag_chunk_library ON rag_document_chunk(library_id);

-- 重跑入库 / 删除某文档全部分块时按 document_id 定位
CREATE INDEX idx_rag_chunk_document ON rag_document_chunk(document_id);

-- 向量索引：cosine 距离（<=>），与 bge-m3 的推荐用法一致
CREATE INDEX idx_rag_chunk_embedding_hnsw
    ON rag_document_chunk USING HNSW (embedding vector_cosine_ops);
