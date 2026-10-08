package com.anomalyco.ifinbrain.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 档案库 RAG（检索增强问答）配置。
 *
 * <p>默认 {@code enabled=false}：RAG 依赖 pgvector，未装 vector 扩展的库打开会直接失败。
 * 用这个开关把档案库 RAG 整体隔离掉，RAG 不用时不影响应用启动。
 *
 * <p>密钥一律走环境变量（{@code RAG_EMBEDDING_API_KEY} 等），不设默认值 —— 与
 * {@code JwtUtil} 同样的姿态：配置缺失要在启动时炸掉，不能带着占位符跑。
 */
@Data
@ConfigurationProperties(prefix = "ifinbrain.rag")
public class RagProperties {

    /** 总开关。false 时 RagConfig 里的所有 bean 都不装配。 */
    private boolean enabled = false;

    // ── embedding（硅基流动，OpenAI 兼容协议）──────────────────────────────

    private String embeddingBaseUrl = "https://api.siliconflow.cn/v1";
    private String embeddingApiKey;
    /** BAAI/bge-m3：1024 维，中文强，官方推荐 cosine 相似度。 */
    private String embeddingModel = "BAAI/bge-m3";
    /** 必须与 V45 迁移里 rag_document_chunk.embedding 的维度一致。 */
    private int dimensions = 1024;

    // ── 对话生成 ────────────────────────────────────────────────────────

    /** 与 embedding 共用同一套 base-url / api-key：硅基流动两者同源，拆开只会让配置自相矛盾。 */
    private String chatModel = "Qwen/Qwen2.5-7B-Instruct";
    private double temperature = 0.2;
    private int maxTokens = 1500;

    // ── 检索与切块 ──────────────────────────────────────────────────────

    /** 向量检索返回的分块数。 */
    private int topK = 6;
    /** 单个分块的目标字符数。中文按字符计，bge-m3 上限 8192 token，取 800 字符较稳。 */
    private int chunkSize = 800;
    /** 相邻分块的重叠字符数，避免答案正好被切在边界上。 */
    private int chunkOverlap = 120;
    /** 候选召回倍数：先取 topK * overFetch 再过滤，最终仍只返回 topK。 */
    private int overFetch = 4;
    /** 相似度下限（cosine 距离，越小越相似）。低于此值的分块丢弃。 */
    private double maxDistance = 0.45;
    /** 单次批量 embedding 的条数。 */
    private int embedBatchSize = 16;
}
