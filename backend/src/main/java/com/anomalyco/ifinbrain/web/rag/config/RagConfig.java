package com.anomalyco.ifinbrain.web.rag.config;

import com.anomalyco.ifinbrain.web.config.RagProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 启用时的启动期校验。
 *
 * <p>与 {@code JwtUtil} / {@code CryptoUtil} 同一姿态：配置缺失在启动时炸掉，
 * 而不是等到用户第一次提问才发现没配 key。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ifinbrain.rag", name = "enabled", havingValue = "true")
public class RagConfig {

    /** pgvector HNSW 索引的硬上限维度。 */
    private static final int HNSW_MAX_DIM = 2000;

    private final RagProperties props;

    @PostConstruct
    void validate() {
        if (props.getEmbeddingApiKey() == null || props.getEmbeddingApiKey().isBlank()) {
            throw new IllegalStateException(
                    "ifinbrain.rag.embedding-api-key must be set (env RAG_API_KEY) when rag is enabled. " +
                    "SiliconFlow key: https://cloud.siliconflow.cn/account/ak");
        }
        if (props.getDimensions() <= 0 || props.getDimensions() > HNSW_MAX_DIM) {
            throw new IllegalStateException(
                    "ifinbrain.rag.dimensions must be 1.." + HNSW_MAX_DIM + " (HNSW hard limit), actual "
                            + props.getDimensions());
        }
        if (props.getTopK() <= 0 || props.getOverFetch() <= 0) {
            throw new IllegalStateException(
                    "ifinbrain.rag.top-k / over-fetch must be > 0, actual topK="
                            + props.getTopK() + " overFetch=" + props.getOverFetch());
        }
        log.info("[rag] enabled | embedding={} ({}d) via {} | chat={} | topK={} chunk={}/{}",
                props.getEmbeddingModel(), props.getDimensions(), props.getEmbeddingBaseUrl(),
                props.getChatModel(), props.getTopK(), props.getChunkSize(), props.getChunkOverlap());
    }
}
