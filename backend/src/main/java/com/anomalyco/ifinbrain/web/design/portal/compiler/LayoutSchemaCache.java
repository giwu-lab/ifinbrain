package com.anomalyco.ifinbrain.web.design.portal.compiler;

import com.anomalyco.ifinbrain.web.design.portal.entity.SysPortalPage;
import com.anomalyco.ifinbrain.web.design.portal.repository.SysPortalPageRepository;
import com.anomalyco.ifinbrain.web.design.portal.service.PortalBlockService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * layout_schema 编译缓存 —— 把 JSON 编译为已解析 JsonNode，
 * 给 /api/portal/resolve 运行时热点路径用。
 *
 * 思想来源：企业级动态数据模型平台文章 §十一「多级缓存 + 版本化 key」。
 * 我们的 MVP 简化：
 *   - L1：Caffeine / ConcurrentHashMap 本地缓存
 *   - 缓存 key：pageId:configVersion（按版本号隔离，与文章 §11.4 "缓存 key 带版本号"一致）
 *   - 不做多实例共享（MVP 单实例即可；后续上 Redis L2）
 *   - 失效策略：保存时 evict(pageId)
 *
 * 注意：缓存的是已解析 JsonNode，不是 Vue 组件树 —— 真正渲染发生在前端 DynamicRenderer。
 * 后端只负责"JSON → IR 解析"这一步；前端再把 IR 映射为 Vue 组件（Registry）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LayoutSchemaCache {

    /** key = pageId, value = (configVersion → parsed JsonNode) */
    private final Map<Long, Map<Integer, JsonNode>> l1 = new ConcurrentHashMap<>();

    private final SysPortalPageRepository pageRepo;
    private final ObjectMapper mapper;

    @PostConstruct
    public void warmup() {
        // 启动时把 status=1 的已发布门户页预热到 L1
        // —— 与文章 §11.2 "对热点模型建议主动预热" 一致
        int warmed = 0;
        for (SysPortalPage p : pageRepo.findAllByOrderByIdAsc()) {
            if (p.getStatus() != null && p.getStatus() == 1) {
                compileAndPut(p.getId(), p.getConfigVersion(), p.getLayoutSchema());
                warmed++;
            }
        }
        if (warmed > 0) log.info("[LayoutSchemaCache] 预热已发布门户页: {} 条", warmed);
    }

    /**
     * 取缓存（编译结果）。未命中则从 schemaJson 编译并放入缓存。
     */
    public JsonNode getOrCompile(Long pageId, Integer configVersion, String schemaJson) {
        Map<Integer, JsonNode> byVersion = l1.computeIfAbsent(pageId, k -> new ConcurrentHashMap<>());
        JsonNode cached = byVersion.get(configVersion);
        if (cached != null) return cached;
        return compileAndPut(pageId, configVersion, schemaJson);
    }

    private JsonNode compileAndPut(Long pageId, Integer configVersion, String schemaJson) {
        JsonNode node;
        try {
            node = mapper.readTree(schemaJson);
        } catch (Exception e) {
            log.warn("[LayoutSchemaCache] pageId={} v{} 解析失败: {}", pageId, configVersion, e.getMessage());
            node = mapper.createObjectNode();
        }
        l1.computeIfAbsent(pageId, k -> new ConcurrentHashMap<>()).put(configVersion, node);
        return node;
    }

    /** 写操作时清缓存 —— 强制下次 resolve 重新编译 */
    public void evict(Long pageId) {
        Map<Integer, JsonNode> removed = l1.remove(pageId);
        if (removed != null) {
            log.info("[LayoutSchemaCache] evict pageId={} ({} versions)", pageId, removed.size());
        }
    }

    public int size() {
        return l1.values().stream().mapToInt(Map::size).sum();
    }
}