package com.anomalyco.ifinbrain.web.report.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.common.PageReq;
import com.anomalyco.ifinbrain.web.common.PageResp;
import com.anomalyco.ifinbrain.web.datamgmt.dto.QueryParamSchema;
import com.anomalyco.ifinbrain.web.datamgmt.dto.QueryResultDto;
import com.anomalyco.ifinbrain.web.datamgmt.entity.SysQuery;
import com.anomalyco.ifinbrain.web.datamgmt.repository.SysQueryRepository;
import com.anomalyco.ifinbrain.web.datamgmt.service.QueryExecutor;
import com.anomalyco.ifinbrain.web.datamgmt.service.QueryService;
import com.anomalyco.ifinbrain.web.report.dto.*;
import com.anomalyco.ifinbrain.web.report.entity.ReportEntity;
import com.anomalyco.ifinbrain.web.report.repository.ReportRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * 报表 CRUD + 取数。
 *
 * <p>取数链路刻意<b>不新造</b>：SQL 渲染 / 安全校验 / 连接池全部委托 {@link QueryExecutor}，
 * 数据源解密委托 {@link QueryService#loadForRun}。本类只负责三件 BI 层不做的事：
 * <ol>
 *   <li>把报表配置（header / columns / pageSize / maxRows）翻译成取数参数；</li>
 *   <li>参数白名单 —— 只放行 query 自己声明过的 name；</li>
 *   <li>max_rows 护栏 —— 让「最多能加载多少行」成为报表级可配的硬约束。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String COPY_SUFFIX = "_copy";
    private static final int CODE_MAX_LEN = 64;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int DEFAULT_MAX_ROWS = 50_000;

    /** 列表页默认排序：最近改的排前面（与改版前全量列表一致） */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("updatedAt"));

    private final ReportRepository repo;
    private final SysQueryRepository queryRepo;
    private final QueryService queryService;
    private final QueryExecutor executor;
    private final PermissionChecker perm;

    // ===================== 查询 =====================

    @Transactional(readOnly = true)
    public PageResp<ReportDto> page(PageReq req) {
        perm.require("report:view");
        String kw = req.getKeyword();
        Page<ReportEntity> p = (kw == null || kw.isBlank())
                ? repo.findAllBy(req.toPageable(DEFAULT_SORT))
                : repo.findByNameContainingIgnoreCaseOrCodeContainingIgnoreCaseOrDescriptionContainingIgnoreCase(
                        kw, kw, kw, req.toPageable(DEFAULT_SORT));
        return PageResp.of(p.getContent().stream().map(this::toDto).toList(),
                p.getTotalElements(), req.safePage(), req.safeSize());
    }

    @Transactional(readOnly = true)
    public ReportDto detail(Long id) {
        perm.require("report:view");
        return toDto(require(id));
    }

    // ===================== 创建 / 更新 =====================

    @Transactional
    public ReportDto create(ReportCreateReq req, String createdBy) {
        perm.require("report:create");

        String code = req.getCode().trim();
        if (repo.existsByCode(code)) {
            throw new BizException(400, "报表编码已存在: " + code);
        }
        // 绑定的 query 必须存在且启用 —— 报表指向一个 disabled 查询等于建了个打不开的空报表
        SysQuery query = requireEnabledQuery(req.getQueryId());

        ReportEntity e = ReportEntity.builder()
                .code(code)
                .name(req.getName().trim())
                .description(req.getDescription())
                .queryId(query.getId())
                .headerJson(writeHeader(req.getHeader()))
                .columnsJson(writeColumns(req.getColumns()))
                .defaultParams(writeDefaultParams(req.getDefaultParams()))
                .pageSize(clampPageSize(req.getPageSize()))
                .maxRows(clampMaxRows(req.getMaxRows()))
                .status((short) 0)
                .createdBy(createdBy == null ? "system" : createdBy)
                .build();
        return toDto(repo.save(e));
    }

    @Transactional
    public ReportDto update(Long id, ReportUpdateReq req) {
        perm.require("report:update");
        ReportEntity e = require(id);

        if (req.getName() != null && !req.getName().isBlank()) {
            e.setName(req.getName().trim());
        }
        if (req.getDescription() != null) {
            e.setDescription(req.getDescription());
        }
        if (req.getQueryId() != null && !req.getQueryId().equals(e.getQueryId())) {
            e.setQueryId(requireEnabledQuery(req.getQueryId()).getId());
            // 换了查询 → 旧的列配置与默认值多半对不上，清掉让设计器重新拉列
            e.setColumnsJson(null);
            e.setDefaultParams(null);
        }
        if (req.getHeader() != null) {
            e.setHeaderJson(writeHeader(req.getHeader()));
        }
        if (req.getColumns() != null) {
            e.setColumnsJson(writeColumns(req.getColumns()));
        }
        if (req.getDefaultParams() != null) {
            e.setDefaultParams(writeDefaultParams(req.getDefaultParams()));
        }
        if (req.getPageSize() != null) {
            e.setPageSize(clampPageSize(req.getPageSize()));
        }
        if (req.getMaxRows() != null) {
            e.setMaxRows(clampMaxRows(req.getMaxRows()));
        }
        return toDto(repo.save(e));
    }

    /** 复制：配置全带走，新 code = 原 code + _copy，状态回到草稿 */
    @Transactional
    public ReportDto copy(Long id, String createdBy) {
        perm.require("report:create");
        ReportEntity src = require(id);
        ReportEntity e = ReportEntity.builder()
                .code(nextCopyCode(src.getCode()))
                .name(src.getName() + " - 副本")
                .description(src.getDescription())
                .queryId(src.getQueryId())
                .headerJson(src.getHeaderJson())
                .columnsJson(src.getColumnsJson())
                .defaultParams(src.getDefaultParams())
                .pageSize(src.getPageSize())
                .maxRows(src.getMaxRows())
                .status((short) 0)
                .createdBy(createdBy == null ? "system" : createdBy)
                .build();
        return toDto(repo.save(e));
    }

    @Transactional
    public void delete(Long id) {
        perm.require("report:delete");
        repo.delete(require(id));
    }

    @Transactional
    public ReportDto publish(Long id) {
        perm.require("report:publish");
        ReportEntity e = require(id);
        e.setStatus((short) 1);
        return toDto(repo.save(e));
    }

    @Transactional
    public ReportDto unpublish(Long id) {
        perm.require("report:publish");
        ReportEntity e = require(id);
        e.setStatus((short) 0);
        return toDto(repo.save(e));
    }

    // ===================== 取数（虚拟滚动分页） =====================

    /**
     * 报表取数一页。
     *
     * <p>内存有上界是这一层的全部意义：不管底表 10 万还是 1000 万行，
     * 单次请求最多把 {@code size} 行收进堆内存。多取 1 行判 hasMore，不跑 COUNT(*)。
     */
    @Transactional(readOnly = true)
    public ReportRunResp run(Long id, ReportRunReq req) {
        perm.require("report:run");
        ReportEntity report = require(id);

        // 已发布报表只有有 view 的人能跑；草稿要 update 权限（等于「能改就能预览」）
        if (report.getStatus() != null && report.getStatus() == 1) {
            perm.require("report:view");
        } else {
            perm.require("report:update");
        }

        SysQuery query = requireEnabledQuery(report.getQueryId());
        List<QueryParamSchema> schema = parseParams(query.getParamsSchemaJson());

        long cap = report.getMaxRows() == null ? DEFAULT_MAX_ROWS : report.getMaxRows();
        long offset = req.getOffset() == null ? 0 : req.getOffset();

        // 情况 1：已经滚过 max_rows 上限。不打 DB，直接回空页。
        //         虚拟滚动会一路滚到这里，每次都真查一次 DB 纯属浪费。
        if (cap > 0 && offset >= cap) {
            return emptyPage(offset, clampSize(req.getSize(), report.getPageSize()), true);
        }

        // 情况 2：本次请求会越过上限。把 size 收到 cap - offset，
        //         否则会白取一批永远不该展示给用户的行。
        int size = clampSize(req.getSize(), report.getPageSize());
        if (cap > 0 && offset + size > cap) {
            size = (int) (cap - offset);
        }

        QueryService.Prepared p = queryService.loadForRun(query.getId());
        QueryExecutor.PagedResult page = executor.executePaged(
                p.ds(), p.plain(), query.getSqlTemplate(), schema,
                sanitizeParams(req.getParams(), schema), offset, size);

        // 这次取了多少行决定后续状态：offset + 实际行数是下一个 offset。
        long next = offset + page.rows().size();
        // 触顶 = 后面无论有没有数据都到头了。hasMore 还要并上它，
        // 否则前端会拿着 hasMore=true 再发一次注定空返回的请求。
        boolean capped = cap > 0 && next >= cap;
        boolean hasMore = page.hasMore() && !capped;

        log.info("[report] id={} queryId={} offset={} size={} rows={} hasMore={} capped={}",
                id, query.getId(), offset, size, page.rows().size(), hasMore, capped);

        return ReportRunResp.builder()
                .columns(page.columns())
                .rows(page.rows())
                .offset(offset)
                .size(size)
                .hasMore(hasMore)
                .capped(capped)
                .nextOffset(next)
                .durationMs(page.durationMs())
                .timeoutSec(page.timeoutSec())
                .build();
    }

    /**
     * 设计器「试跑」：按 queryId 取一次首屏，**不落库**。
     *
     * <p>与 {@link #run} 的三点差异，都服务于「试跑不产生副作用」：
     * <ol>
     *   <li>不要求报表已存在 —— 新建报表还没有 id，同样能先看数据再决定要不要存；</li>
     *   <li>不套 max_rows / pageSize 这类报表级护栏 —— 那些要等报表存了才有意义，
     *       试跑只看第一页够确认列了；</li>
     *   <li>offset 恒为 0 —— 设计器是「看一眼列长什么样」，不是虚拟滚动翻页。</li>
     * </ol>
     *
     * <p>权限用 report:create：能建报表就能试跑；{@link #listQueryOptions} 用的是同一档，
     * 这样「下拉里能选到的查询」与「能试跑的查询」永远一致，不会出现选得到却跑不了。
     * 参数依然过 {@link #sanitizeParams} 白名单 —— 换了入口不等于放开注入面。
     */
    @Transactional(readOnly = true)
    public ReportRunResp preview(ReportPreviewReq req) {
        perm.require("report:create");
        SysQuery query = requireEnabledQuery(req.getQueryId());
        List<QueryParamSchema> schema = parseParams(query.getParamsSchemaJson());
        // 第二个参数传 null = 不吃「报表配置的 pageSize」，落到 DEFAULT_PAGE_SIZE
        int size = clampSize(req.getSize(), null);

        QueryService.Prepared p = queryService.loadForRun(query.getId());
        QueryExecutor.PagedResult page = executor.executePaged(
                p.ds(), p.plain(), query.getSqlTemplate(), schema,
                sanitizeParams(req.getParams(), schema), 0L, size);

        log.info("[report] preview queryId={} size={} rows={} hasMore={}",
                query.getId(), size, page.rows().size(), page.hasMore());

        return ReportRunResp.builder()
                .columns(page.columns())
                .rows(page.rows())
                .offset(0L)
                .size(size)
                .hasMore(page.hasMore())
                .capped(false)
                .nextOffset((long) page.rows().size())
                .durationMs(page.durationMs())
                .timeoutSec(page.timeoutSec())
                .build();
    }

    // ===================== 工具 =====================

    private ReportEntity require(Long id) {
        return repo.findById(id).orElseThrow(() -> new BizException(404, "报表不存在: " + id));
    }

    private SysQuery requireEnabledQuery(Long queryId) {
        if (queryId == null) throw new BizException(400, "必须绑定一个数据源查询");
        SysQuery q = queryRepo.findById(queryId)
                .orElseThrow(() -> new BizException(400, "数据源查询不存在: " + queryId));
        if (q.getStatus() == null || q.getStatus() != 1) {
            throw new BizException(400, "数据源查询已停用，无法绑定到报表: " + queryId);
        }
        return q;
    }

    /**
     * 参数白名单。
     *
     * <p>只放行 query 自己 schema 里声明过的 name。前端可以传任意 JSON，
     * 不做这一步就等于让调用方往渲染阶段塞未知 key —— 虽然 PreparedStatement 已经挡了注入，
     * 但仍可能用没声明的参数名探测 SQL 结构（报错信息差异可用于盲注）。
     * 顺带把 null value 抹平，避免把 null 值塞进 PreparedStatement 造成类型推断问题。
     */
    private Map<String, String> sanitizeParams(Map<String, String> raw, List<QueryParamSchema> schema) {
        if (raw == null || raw.isEmpty() || schema.isEmpty()) return Map.of();
        Set<String> allowed = new HashSet<>();
        for (QueryParamSchema p : schema) allowed.add(p.getName());

        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (e.getKey() != null && allowed.contains(e.getKey()) && e.getValue() != null) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** 空页响应（已触 max_rows 上限时用）；不查 DB，columns 也省了 —— 前端此时已有列定义 */
    private ReportRunResp emptyPage(long offset, int size, boolean capped) {
        return ReportRunResp.builder()
                .columns(List.of())
                .rows(List.of())
                .offset(offset)
                .size(size)
                .hasMore(false)
                .capped(capped)
                .nextOffset(offset)
                .durationMs(0L)
                .timeoutSec(QueryExecutor.TIMEOUT_SECONDS)
                .build();
    }

    /** 请求 size 优先，否则用报表配置，最后落到默认；统一夹到 1..MAX_PAGE_SIZE */
    private int clampSize(Integer requested, Integer configured) {
        int v = requested != null ? requested
                : (configured != null ? configured : DEFAULT_PAGE_SIZE);
        return (int) Math.max(1, Math.min(v, QueryExecutor.MAX_PAGE_SIZE));
    }

    private int clampPageSize(Integer v) {
        if (v == null) return DEFAULT_PAGE_SIZE;
        return (int) Math.max(20, Math.min(v, QueryExecutor.MAX_PAGE_SIZE));
    }

    /** maxRows 兜底：null → 50000；负数（不该发生，DB 有 CHECK）→ 0 不限制 */
    private int clampMaxRows(Integer v) {
        if (v == null) return DEFAULT_MAX_ROWS;
        return Math.max(0, v);
    }

    private ReportDto toDto(ReportEntity e) {
        SysQuery q = queryRepo.findById(e.getQueryId()).orElse(null);
        return ReportDto.builder()
                .id(e.getId())
                .code(e.getCode())
                .name(e.getName())
                .description(e.getDescription())
                .queryId(e.getQueryId())
                .queryName(q == null ? null : q.getName())
                .header(readHeader(e.getHeaderJson()))
                .columns(readColumns(e.getColumnsJson()))
                .defaultParams(readDefaultParams(e.getDefaultParams()))
                .pageSize(e.getPageSize())
                .maxRows(e.getMaxRows())
                .status(e.getStatus())
                .createdBy(e.getCreatedBy())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    // ---------- JSON 读写（解析失败一律降级，不让列表页挂掉） ----------

    private ReportDto.HeaderLayout readHeader(String json) {
        if (json == null || json.isBlank()) return ReportDto.HeaderLayout.defaultLayout();
        try {
            ReportDto.HeaderLayout h = MAPPER.readValue(json, ReportDto.HeaderLayout.class);
            return h == null ? ReportDto.HeaderLayout.defaultLayout() : h;
        } catch (Exception ex) {
            log.warn("[report] header_json 解析失败，降级为默认布局: {}", ex.getMessage());
            return ReportDto.HeaderLayout.defaultLayout();
        }
    }

    private List<ReportDto.ColumnCfg> readColumns(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<ReportDto.ColumnCfg> list = MAPPER.readValue(json,
                    new TypeReference<List<ReportDto.ColumnCfg>>() {});
            return list == null ? List.of() : list;
        } catch (Exception ex) {
            log.warn("[report] columns_json 解析失败，按无列配置处理: {}", ex.getMessage());
            return List.of();
        }
    }

    private Map<String, String> readDefaultParams(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, String> m = MAPPER.readValue(json, new TypeReference<Map<String, String>>() {});
            return m == null ? Map.of() : m;
        } catch (Exception ex) {
            log.warn("[report] default_params 解析失败，按空处理: {}", ex.getMessage());
            return Map.of();
        }
    }

    private List<QueryParamSchema> parseParams(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<QueryParamSchema> list = MAPPER.readValue(json,
                    new TypeReference<List<QueryParamSchema>>() {});
            return list == null ? List.of() : list;
        } catch (Exception ex) {
            log.warn("[report] query params_schema 解析失败，按无参数处理: {}", ex.getMessage());
            return List.of();
        }
    }

    private String writeHeader(ReportDto.HeaderLayout h) {
        if (h == null) return null;
        try {
            return MAPPER.writeValueAsString(h);
        } catch (Exception ex) {
            throw new BizException(400, "查询头布局序列化失败: " + ex.getMessage());
        }
    }

    private String writeColumns(List<ReportDto.ColumnCfg> cols) {
        if (cols == null) return null;
        try {
            return MAPPER.writeValueAsString(cols);
        } catch (Exception ex) {
            throw new BizException(400, "列配置序列化失败: " + ex.getMessage());
        }
    }

    private String writeDefaultParams(Map<String, String> m) {
        if (m == null) return null;
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception ex) {
            throw new BizException(400, "默认值序列化失败: " + ex.getMessage());
        }
    }

    /** 原 code 加 _copy；已存在则叠加 _copy2 … */
    private String nextCopyCode(String base) {
        String root = base == null ? "report" : base;
        int room = CODE_MAX_LEN - COPY_SUFFIX.length();
        if (root.length() > room) root = root.substring(0, room);

        String candidate = root + COPY_SUFFIX;
        for (int seq = 2; seq <= 100; seq++) {
            if (!repo.existsByCode(candidate)) return candidate;
            String suffix = COPY_SUFFIX + seq;
            candidate = root.substring(0, Math.min(root.length(), CODE_MAX_LEN - suffix.length())) + suffix;
        }
        throw new BizException(400, "同名副本过多，无法自动生成编码: " + base);
    }

    /**
     * 供设计器拉「可绑定的查询列表」。
     *
     * <p>与 {@code BiDataService.listQueryOptions} 同思路：不返回 sqlTemplate，
     * 少一个 SQL 泄露面。权限用 report:create（设计时需要能选查询）。
     */
    @Transactional(readOnly = true)
    public List<QueryOption> listQueryOptions() {
        perm.require("report:create");
        List<QueryOption> out = new ArrayList<>();
        for (SysQuery q : queryRepo.findAllByOrderByIdAsc()) {
            if (q.getStatus() == null || q.getStatus() != 1) continue;
            out.add(QueryOption.builder()
                    .id(q.getId())
                    .name(q.getName())
                    .description(q.getDescription())
                    .params(parseParams(q.getParamsSchemaJson()))
                    .build());
        }
        return out;
    }
}
