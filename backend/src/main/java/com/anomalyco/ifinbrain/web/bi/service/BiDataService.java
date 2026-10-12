package com.anomalyco.ifinbrain.web.bi.service;

import com.anomalyco.ifinbrain.web.bi.dto.BiDataQueryReq;
import com.anomalyco.ifinbrain.web.bi.dto.BiDataResp;
import com.anomalyco.ifinbrain.web.bi.dto.BiQueryOptionDto;
import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.datamgmt.dto.QueryParamSchema;
import com.anomalyco.ifinbrain.web.datamgmt.dto.QueryResultDto;
import com.anomalyco.ifinbrain.web.datamgmt.entity.SysDatasource;
import com.anomalyco.ifinbrain.web.datamgmt.entity.SysQuery;
import com.anomalyco.ifinbrain.web.datamgmt.repository.SysDatasourceRepository;
import com.anomalyco.ifinbrain.web.datamgmt.repository.SysQueryRepository;
import com.anomalyco.ifinbrain.web.datamgmt.service.QueryExecutor;
import com.anomalyco.ifinbrain.web.datamgmt.service.QueryService;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 大屏取数层。
 *
 * <p>为什么需要这一层（而不是让播放页直接调 {@code /api/datamgmt/queries/{id}/run}）：
 * 一个 12 组件的大屏 × 20 个观看者 = 240 次并发查询，不限流会直接打爆 HikariCP 连接池。
 * 所以 BI 相对现有 QueryService 的唯一新增风险就在这里，用两件事兜住：
 * <ol>
 *   <li><b>TTL 缓存</b>：同一 query + 同一 params 在 TTL 内只打一次 DB；</li>
 *   <li><b>并发信号量</b>：{@code tryAcquire(0)} 拿不到立刻 429，不排队饿死。</li>
 * </ol>
 *
 * <p>SQL 渲染 / 安全校验全部委托 {@link QueryExecutor}（内含 SqlSafetyChecker），
 * 本类不碰 SQL，也不自己解密数据源密码 —— 那些由 {@link QueryService#loadForRun} 负责。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BiDataService {

    /** 缓存 TTL 默认值 / 上限（秒） */
    private static final int DEFAULT_TTL_SEC = 30;
    private static final int MAX_TTL_SEC = 300;
    /** 超过 TTL 的两倍还没被再次访问的条目，写缓存前顺手清掉 */
    private static final long STALE_FACTOR = 2L;

    private final QueryService queryService;
    private final QueryExecutor executor;
    private final PermissionChecker perm;
    private final SysQueryRepository queryRepo;
    private final SysDatasourceRepository dsRepo;

    @Value("${bi.data.max-concurrent:8}")
    private int maxConcurrent = 8;

    /** queryId|paramsHash → 结果 + 写入时刻 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private Semaphore gate;

    @PostConstruct
    void initGate() {
        int n = Math.max(1, maxConcurrent);
        this.gate = new Semaphore(n);
        log.info("[bi] 取数并发闸门就绪：max-concurrent={}，缓存 TTL 默认 {}s / 上限 {}s", n, DEFAULT_TTL_SEC, MAX_TTL_SEC);
    }

    /**
     * 大屏取数主入口。
     *
     * <p>流程：权限 → 缓存 → 信号量 → 委托执行 → 写缓存。
     * 失败（{@link BizException}）原样抛出、**不写缓存** —— 播放页据此保留上一帧画面。
     */
    public BiDataResp query(BiDataQueryReq req) {
        perm.require("bi:dataset:view");
        return queryAuthorized(req);
    }

    /**
     * 取数执行逻辑（**不做权限检查**）。
     *
     * <p>供已经自行鉴权、且权限语义与 {@code bi:dataset:view} 不同的调用方复用 ——
     * 目前只有表单模块的动态选项（{@code design/forms/options}）。
     *
     * <p>⚠️ <b>调用方必须自己 perm.require</b>。这里刻意不设默认放行，是为了让
     * 「谁有权读这条 SQL」这个问题在调用点上可见，而不是藏在一个公共工具方法里。
     * 表单填表人只需要 {@code design:form:view}，不该被要求持有 BI 大屏权限。
     */
    public BiDataResp queryAuthorized(BiDataQueryReq req) {
        int ttlSec = normalizeTtl(req.getCacheSec());
        String key = cacheKey(req.getQueryId(), req.getParams());

        CacheEntry hit = cache.get(key);
        if (hit != null) {
            long age = System.currentTimeMillis() - hit.writtenAt();
            if (age < ttlSec * 1000L) {
                return hit.resp().asCached(age);
            }
            cache.remove(key, hit);
        }

        // tryAcquire(0)：拿不到就 429，绝不排队 —— 排队只会把大屏拖成慢动作，而不是保护数据库
        if (!gate.tryAcquire()) {
            log.warn("[bi] 取数并发已满（max={}），拒绝 queryId={}", maxConcurrent, req.getQueryId());
            throw new BizException(429, "大屏数据繁忙，请稍后重试");
        }
        try {
            evictStale(ttlSec);

            QueryService.Prepared p = queryService.loadForRun(req.getQueryId());
            QueryResultDto r = executor.execute(p.ds(), p.plain(), p.query().getSqlTemplate(),
                    p.schema(), req.getParams() == null ? Map.of() : req.getParams());

            BiDataResp resp = BiDataResp.builder()
                    .columns(r.getColumns())
                    .rows(r.getRows())
                    .rowCount(r.getRowCount())
                    .truncated(r.isTruncated())
                    .durationMs(r.getDurationMs())
                    .cached(false)
                    .cacheAgeMs(0)
                    .build();

            cache.put(key, new CacheEntry(resp, System.currentTimeMillis()));
            log.info("[bi] queryId={} rows={} truncated={} durationMs={} ttl={}s",
                    req.getQueryId(), resp.getRowCount(), resp.isTruncated(),
                    resp.getDurationMs(), ttlSec);
            return resp;
        } finally {
            gate.release();
        }
    }

    // ===================== 可绑定查询列表 =====================

    /**
     * 数据绑定面板的候选查询列表。
     *
     * <p>权限用 {@code bi:dataset:view} 而不是 {@code data:query:view}：
     * viewer 角色被设计成「能看大屏、不能进数据管理」，因此不能直接复用
     * {@code QueryService.list()}（它要求 data:query:view）。这里另查一次，
     * 只暴露选查询必需的字段，<b>不返回 sqlTemplate</b> —— 少一个 SQL 泄露面。
     */
    @Transactional(readOnly = true)
    public List<BiQueryOptionDto> listQueryOptions() {
        perm.require("bi:dataset:view");

        List<SysQuery> all = queryRepo.findAllByOrderByIdAsc();
        Map<Long, String> dsNames = new HashMap<>();
        for (SysDatasource ds : dsRepo.findAllByOrderByIdAsc()) {
            dsNames.put(ds.getId(), ds.getName());
        }

        List<BiQueryOptionDto> out = new ArrayList<>(all.size());
        for (SysQuery q : all) {
            if (q.getStatus() == null || q.getStatus() != 1) continue;   // 只给启用中的
            out.add(BiQueryOptionDto.builder()
                    .id(q.getId())
                    .name(q.getName())
                    .description(q.getDescription())
                    .datasourceId(q.getDatasourceId())
                    .datasourceName(dsNames.get(q.getDatasourceId()))
                    .paramsSchema(parseParams(q.getParamsSchemaJson()))
                    .build());
        }
        return out;
    }

    /**
     * params_schema_json → 列表。解析失败降级为空列表：
     * 一个查询的 schema 坏了不该让整个绑定面板 500（其他查询照样能选）。
     */
    private List<BiQueryOptionDto.QueryParam> parseParams(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<QueryParamSchema> raw = new tools.jackson.databind.ObjectMapper()
                    .readValue(json, new tools.jackson.core.type.TypeReference<
                            List<QueryParamSchema>>() {});
            if (raw == null) return List.of();
            List<BiQueryOptionDto.QueryParam> out = new ArrayList<>(raw.size());
            for (QueryParamSchema p : raw) {
                out.add(BiQueryOptionDto.QueryParam.builder()
                        .name(p.getName())
                        .label(p.getLabel())
                        .type(p.getType())
                        .required(p.getRequired())
                        .defaultValue(p.getDefaultValue())
                        .placeholder(p.getPlaceholder())
                        .build());
            }
            return out;
        } catch (Exception e) {
            log.warn("[bi] 查询参数 schema 解析失败，按无参数处理：{}", e.getMessage());
            return List.of();
        }
    }

    // ===================== 工具 =====================

    /** TTL 归一化：null / <=0 → 30；>300 → 300 */
    private int normalizeTtl(Integer cacheSec) {
        if (cacheSec == null || cacheSec <= 0) return DEFAULT_TTL_SEC;
        return Math.min(cacheSec, MAX_TTL_SEC);
    }

    /**
     * 缓存 key = queryId + "|" + paramsHash。
     *
     * <p>params 先按 key 排序再摘要 —— 同一组参数无论前端传的对象键序如何，都落到同一个 key。
     * 用 SHA-256 而不是 String.hashCode()：哈希撞了就会给用户返回别人的数据。
     */
    private String cacheKey(Long queryId, Map<String, String> params) {
        return queryId + "|" + sha256(canonical(params));
    }

    private static String canonical(Map<String, String> params) {
        if (params == null || params.isEmpty()) return "";
        Map<String, String> sorted = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            sb.append(e.getKey()).append('=')
              .append(e.getValue() == null ? "" : e.getValue())
              .append('\u0001');
        }
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {          // 前 16 字节 = 128 bit，够用
                hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new BizException(500, "缓存 key 计算失败: " + ex.getMessage());
        }
    }

    /** 写缓存前清理「超龄一倍 TTL 还没人再碰」的条目，防止 map 无限涨 */
    private void evictStale(int ttlSec) {
        long now = System.currentTimeMillis();
        long limit = ttlSec * 1000L * STALE_FACTOR;
        int before = cache.size();
        cache.entrySet().removeIf(e -> now - e.getValue().writtenAt() > limit);
        int removed = before - cache.size();
        if (removed > 0) log.info("[bi] 清理过期缓存条目 {} 个，剩余 {} 个", removed, cache.size());
    }

    /** 缓存条目：结果 + 写入时刻 */
    private record CacheEntry(BiDataResp resp, long writtenAt) {}
}
