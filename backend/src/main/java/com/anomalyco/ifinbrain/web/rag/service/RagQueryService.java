package com.anomalyco.ifinbrain.web.rag.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.config.RagProperties;
import com.anomalyco.ifinbrain.web.documents.entity.ArchiveLibrary;
import com.anomalyco.ifinbrain.web.documents.repository.ArchiveLibraryRepository;
import com.anomalyco.ifinbrain.web.rag.repository.RagChunkRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 档案库问答：把用户问题向量化 → 在<b>用户有权访问的档案库范围内</b>做向量检索 → 交给 LLM 生成答案。
 *
 * <p>条件装配：RAG 关闭时完全不注册。EmbeddingModel / ChatClient 来自 Spring AI 的
 * 自动配置，只在 {@code spring.ai.model.*} 打开时存在；若在这里无条件注入，
 * rag.enabled=false 的环境会在启动时因找不到 bean 而失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ifinbrain.rag", name = "enabled", havingValue = "true")
public class RagQueryService {

    private static final String SYSTEM_PROMPT = """
            你是企业内部档案问答助手。你只能依据下面提供的「参考资料」回答问题。

            规则：
            1. 参考资料里没有的内容，一律回答「根据现有档案无法回答该问题」，不要推测、不要编造。
            2. 引用具体结论时，标明来源文件名，例如（来源：合同.pdf）。
            3. 多份资料互相矛盾时，把矛盾点指出来，不要自行选一个。
            4. 回答用简体中文，简洁直接，不要复述问题，不要写「根据参考资料」这类开场白。
            """;

    private final EmbeddingModel embeddingModel;
    private final ChatModel chatModel;
    private final RagChunkRepository chunks;
    private final ArchiveLibraryRepository libraryRepo;
    private final PermissionChecker perm;
    private final RagProperties props;

    /** 一条检索命中，带来源信息。documentId 用于前端溯源回跳到原档案。 */
    public record Citation(Long documentId, String filename, int chunkIndex, double distance, String snippet) {
    }

    public record Answer(String answer, List<Citation> citations, long elapsedMs) {
    }

    /**
     * 权限过滤的唯一依据：<b>重新查 DB 拿档案库授权</b>，绝不信任向量表里存的用户信息。
     * 向量表里的 library_id 只是检索范围，最终返回的每条结果都由这里算出的集合圈定。
     */
    private List<Long> allowedLibraryIds() {
        return libraryRepo.findAllByStatusOrderBySortAscIdAsc((short) 1).stream()
                .filter(lib -> perm.has("documents:" + lib.getSlug() + ":view"))
                .map(ArchiveLibrary::getId)
                .toList();
    }

    @Transactional(readOnly = true)
    public Answer ask(String question) {
        long t0 = System.currentTimeMillis();
        if (question == null || question.isBlank()) {
            throw new BizException(400, "问题不能为空");
        }
        String q = question.strip();

        List<Long> allowed = allowedLibraryIds();
        if (allowed.isEmpty()) {
            // 关键：没有授权集合时直接拒绝，绝不能退化成「不加过滤 = 全库可搜」。
            throw new BizException(403, "当前账号没有任何档案库的查看权限，无法提问");
        }

        float[] qv = embeddingModel.embed(q);
        List<RagChunkRepository.ScoredChunk> hits = chunks.search(
                qv, allowed, props.getTopK() * props.getOverFetch(), props.getMaxDistance());

        if (hits.isEmpty()) {
            return new Answer("根据现有档案无法回答该问题。", List.of(), System.currentTimeMillis() - t0);
        }
        // 召回时多取了些，这里截到 topK，保证返回给模型的上下文体量可控
        List<RagChunkRepository.ScoredChunk> top = hits.size() > props.getTopK()
                ? hits.subList(0, props.getTopK())
                : hits;

        StringBuilder context = new StringBuilder();
        List<Citation> citations = new ArrayList<>(top.size());
        int i = 1;
        for (RagChunkRepository.ScoredChunk h : top) {
            context.append("\n--- 资料 ").append(i++).append("（来源：")
                    .append(h.sourceFilename() == null ? "未知" : h.sourceFilename())
                    .append("）---\n").append(h.content());
            citations.add(new Citation(
                    h.documentId(), h.sourceFilename(), h.chunkIndex(), h.distance(),
                    h.content().length() > 160 ? h.content().substring(0, 160) + "…" : h.content()));
        }

        // defaultSystem/defaultUser 是 ChatClient.Builder 上的方法，必须在 build() 之前设置。
        // 这里每次从 ChatModel 现场构建，不复用注入的 Builder —— 避免默认提示词在多次
        // 提问间累积叠加（Builder 是可变的，defaultSystem 追加而非替换）。
        String answer = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultUser(u -> u.text("""
                        用户问题：{q}

                        参考资料：
                        {ctx}
                        """).param("q", q).param("ctx", context.toString()))
                .build()
                .prompt()
                .call()
                .content();

        log.info("[rag] ask ok, allowedLibraries={} hits={} elapsed={}ms",
                allowed.size(), top.size(), System.currentTimeMillis() - t0);
        return new Answer(answer == null ? "" : answer, citations, System.currentTimeMillis() - t0);
    }
}
