package com.anomalyco.ifinbrain.web.report.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.common.EntityAuthSupport;
import com.anomalyco.ifinbrain.web.common.MenuPublisher;
import com.anomalyco.ifinbrain.web.common.PageReq;
import com.anomalyco.ifinbrain.web.common.PageResp;
import com.anomalyco.ifinbrain.web.common.PublishMenuReq;
import com.anomalyco.ifinbrain.web.datamgmt.common.QueryExport;
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
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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

    /** 实体级权限码前缀：report:{code}:{verb} */
    public static final String PERM_PREFIX = "report";

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
    private final MenuPublisher menuPublisher;
    private final PermissionChecker perm;
    private final EntityAuthSupport auth;

    // ===================== 查询 =====================

    @Transactional(readOnly = true)
    public PageResp<ReportDto> page(PageReq req) {
        Set<String> allowed = auth.allowedCodes(PERM_PREFIX, "view");
        if (allowed.isEmpty()) return PageResp.of(List.of(), 0, req.safePage(), req.safeSize());
        String kw = req.getKeyword();
        Page<ReportEntity> p = (kw == null || kw.isBlank())
                ? repo.findAllByCodeIn(allowed, req.toPageable(DEFAULT_SORT))
                : repo.findByNameContainingIgnoreCaseOrCodeContainingIgnoreCaseOrDescriptionContainingIgnoreCaseAndCodeIn(
                        kw, kw, kw, allowed, req.toPageable(DEFAULT_SORT));
        return PageResp.of(p.getContent().stream().map(this::toDto).toList(),
                p.getTotalElements(), req.safePage(), req.safeSize());
    }

    /** 详情：实体级 view —— 非授权用户直接探测 id 会被挡在 403 */
    @Transactional(readOnly = true)
    public ReportDto detail(Long id) {
        ReportEntity e = require(id);
        auth.require(PERM_PREFIX, e.getCode(), "view");
        return toDto(e);
    }

    /** 设计器读报表：要求实体级 design 权限（能看 ≠ 能改） */
    @Transactional(readOnly = true)
    public ReportDto detailForDesign(Long id) {
        ReportEntity e = require(id);
        auth.require(PERM_PREFIX, e.getCode(), "design");
        return toDto(e);
    }

    // ===================== 实体级授权 =====================

    /** 当前这张报表的授权：{view:[roleId], design:[...], publish:[...], delete:[...]} */
    @Transactional(readOnly = true)
    public Map<String, List<Long>> getPermissions(Long id) {
        perm.require("report:update");
        return auth.getPermissions(PERM_PREFIX, require(id).getCode());
    }

    /**
     * 覆盖式写入授权。门槛用模块级 {@code report:update} 而不是「持有这张报表的
     * update」—— 否则只能编辑自己报表的人给别人的报表授权不了，
     * 而这恰恰是管理员最需要做的事。
     */
    @Transactional
    public void updatePermissions(Long id, Map<String, List<Long>> grants) {
        perm.require("report:update");
        String code = require(id).getCode();
        auth.updatePermissions(PERM_PREFIX, code, grants);
        log.info("[report] updated permissions for report id={} code={}", id, code);
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
        ReportEntity saved = repo.save(e);
        // 建实体 = 注册 4 个实体级权限码 + seed 给 admin（只有 admin）
        auth.onEntityCreated(PERM_PREFIX, code, saved.getName());
        log.info("[report] created report id={} code={} 4 perms + admin seeded", saved.getId(), code);
        return toDto(saved);
    }

    @Transactional
    public ReportDto update(Long id, ReportUpdateReq req) {
        perm.require("report:update");
        ReportEntity e = require(id);
        auth.require(PERM_PREFIX, e.getCode(), "design");

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
        // 复制要先能看原件，否则等于有 create 权限就能把别人的报表内容原样搬走
        auth.require(PERM_PREFIX, src.getCode(), "view");
        String code = nextCopyCode(src.getCode());
        ReportEntity e = ReportEntity.builder()
                .code(code)
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
        ReportEntity copy = repo.save(e);
        auth.onEntityCreated(PERM_PREFIX, code, copy.getName());
        return toDto(copy);
    }

    @Transactional
    public void delete(Long id) {
        perm.require("report:delete");
        ReportEntity e = require(id);
        auth.require(PERM_PREFIX, e.getCode(), "delete");
        // 菜单一并下线：报表都没了，留个指向 404 的侧边栏入口只会让人以为系统坏了
        menuPublisher.unpublish(menuPath(id));
        repo.delete(e);
        // 权限码一并清掉，避免 code 复用时新报表白捡上一任的授权
        auth.revokeEntity(PERM_PREFIX, e.getCode());
    }

    // ===================== 发布（生成菜单入口） =====================

    /**
     * 发布：把报表挂到菜单树的某个目录下，侧边栏出现一个入口。
     *
     * <p><b>发布到底做了什么</b> —— 之前这里只翻了个 status 位，那个位唯一的用处是在
     * {@link #run} 里切换校验：已发布要 {@code report:view}，草稿要 {@code report:update}，
     * 也就是「把编辑权换成查看权」。除此之外报表哪儿都不出现，侧边栏没有入口 ——
     * 已发布的报表别人根本找不到。现在补上菜单这一步，发布才名副其实。
     *
     * <p>菜单的建/改/软删交给 {@link MenuPublisher}，与大屏共用同一套逻辑
     * （见该类注释：为什么靠 path 反查、为什么 permission 就是角色可见性的开关）。
     */
    @Transactional
    public ReportDto publish(Long id, PublishMenuReq body) {
        perm.require("report:publish");
        ReportEntity e = require(id);
        auth.require(PERM_PREFIX, e.getCode(), "publish");
        requireEnabledQuery(e.getQueryId());
        // 菜单行的 permission 改成实体级 view —— 侧边栏入口跟着实体授权走，
        // 收回某张报表的 view，入口对没权限的角色自然消失
        menuPublisher.publish(menuPath(id), auth.permCode(PERM_PREFIX, e.getCode(), "view"), body, e.getName());
        e.setStatus((short) 1);
        return toDto(repo.save(e));
    }

    /** 取消发布：报表退回草稿 + 菜单软下线（status=0，可重新发布恢复） */
    @Transactional
    public ReportDto unpublish(Long id) {
        perm.require("report:publish");
        ReportEntity e = require(id);
        auth.require(PERM_PREFIX, e.getCode(), "publish");
        menuPublisher.unpublish(menuPath(id));
        e.setStatus((short) 0);
        return toDto(repo.save(e));
    }

    /** 可选父目录列表（发布对话框的下拉） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> publishDirs() {
        perm.require("report:publish");
        return menuPublisher.dirs();
    }

    /**
     * 菜单 path = /bi/report/view/{reportId}。
     *
     * <p>与报表 id 一一对应，所以既能反查菜单（{@link MenuPublisher#find}），
     * 又是一条稳定可收藏的直链。
     */
    private static String menuPath(Long reportId) {
        return "/bi/report/view/" + reportId;
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

        // 已发布报表只有有实体级 view 的人能跑；草稿要实体级 design（等于「能改就能预览」）
        if (report.getStatus() != null && report.getStatus() == 1) {
            auth.require(PERM_PREFIX, report.getCode(), "view");
        } else {
            auth.require(PERM_PREFIX, report.getCode(), "design");
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

        /*
         * 页码分页器需要 total。这里多跑一次 COUNT(*) —— 只在首次查询（offset=0）时跑，
         * 翻页复用前端已有的 total，不必每页都付这个成本。
         *
         * 两个护栏：
         * 1. count 失败（方言不支持派生表包裹 / 超时）返回 -1，前端退化成 hasMore 判断；
         * 2. maxRows 封顶 —— 用户能翻到的最后一页就是护栏那一页，
         *    显示 5 万条而实际有 900 万条会让人以为数据丢了。
         */
        long total = -1;
        if (offset == 0 && req.isWithTotal()) {
            total = executor.countRows(p.ds(), p.plain(), query.getSqlTemplate(),
                    schema, sanitizeParams(req.getParams(), schema));
            if (total > 0 && cap > 0) total = Math.min(total, cap);
        }

        log.info("[report] id={} queryId={} offset={} size={} rows={} hasMore={} capped={} total={}",
                id, query.getId(), offset, size, page.rows().size(), hasMore, capped, total);

        return ReportRunResp.builder()
                .columns(page.columns())
                .rows(page.rows())
                .offset(offset)
                .size(size)
                .total(total)
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

    // ===================== 导出 =====================

    /**
     * 导出前的公共准备：鉴权 + 取报表 + 解析配置。
     *
     * <p>权限判据与 {@link #run} 完全一致 —— 已发布要实体级 {@code view}，
     * 草稿要实体级 {@code design}。导出本质是取数的一种，不能绕过这条线。
     */
    private ExportCtx prepareExport(Long id, Map<String, String> params) {
        perm.require("report:run");
        ReportEntity report = require(id);
        if (report.getStatus() != null && report.getStatus() == 1) {
            auth.require(PERM_PREFIX, report.getCode(), "view");
        } else {
            auth.require(PERM_PREFIX, report.getCode(), "design");
        }
        SysQuery query = requireEnabledQuery(report.getQueryId());
        List<QueryParamSchema> schema = parseParams(query.getParamsSchemaJson());
        return new ExportCtx(
                report,
                queryService.loadForRun(query.getId()),
                query,
                schema,
                sanitizeParams(params, schema),
                parseColumns(report.getColumnsJson()));
    }

    /**
     * 流式 CSV 导出：直接写 OutputStream，不在堆里拼整个文件。
     *
     * <p>刻意<b>不</b>复用 {@link #run} 的分页路径再拼接：那是给虚拟滚动用的，
     * 护栏上限 5 万行时一个 List&lt;Object[]&gt; 就是几十 MB 堆内存。
     * 导出必须一次流过去，内存占用与结果集大小无关。
     */
    @Transactional(readOnly = true)
    public void exportCsv(Long id, Map<String, String> params, HttpServletResponse resp) throws Exception {
        ExportCtx ctx = prepareExport(id, params);
        String fname = safeFileName(ctx.report().getName()) + "-" + LocalDate.now() + ".csv";

        resp.setContentType("text/csv; charset=utf-8");
        resp.setCharacterEncoding("UTF-8");
        setDisposition(resp, fname);

        long[] written = {0};
        String[][] keys = new String[1][];
        try (OutputStream os = resp.getOutputStream();
             Writer w = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
            // UTF-8 BOM：Excel 打开 CSV 中文不乱码
            os.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            iterateExport(ctx, new QueryExecutor.RowHandler() {
                @Override
                public void onHeaders(List<QueryResultDto.Column> columns) {
                    String[] ks = new String[columns.size()];
                    for (int i = 0; i < ks.length; i++) ks[i] = columns.get(i).getKey();
                    keys[0] = ks;
                    try {
                        QueryExport.writeCsvRow(w, ctx.headerLabels(columns).toArray());
                    } catch (Exception ex) { throw new RuntimeException(ex); }
                }
                @Override
                public void onRow(List<Object> values) {
                    if (!ctx.allow(written[0])) return;
                    written[0]++;
                    try {
                        QueryExport.writeCsvRow(w, ctx.pick(values, keys[0]).toArray());
                    } catch (Exception ex) { throw new RuntimeException(ex); }
                }
            });
            w.flush();
        }
        log.info("[report] export csv id={} rows={} capped={}", id, written[0], ctx.isCapped(written[0]));
    }

    /** 流式 XLSX：SXSSFWorkbook(100) 行级流式，内存只保留最近 100 行 */
    @Transactional(readOnly = true)
    public void exportXlsx(Long id, Map<String, String> params, HttpServletResponse resp) throws Exception {
        ExportCtx ctx = prepareExport(id, params);
        String fname = safeFileName(ctx.report().getName()) + "-" + LocalDate.now() + ".xlsx";

        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        setDisposition(resp, fname);

        long[] written = {0};
        int[] rowIdx = {0};
        String[][] keys = new String[1][];
        SXSSFWorkbook wb = new SXSSFWorkbook(100);
        try {
            SXSSFSheet sheet = wb.createSheet("data");
            CellStyle headerStyle = QueryExport.createHeaderStyle(wb);

            iterateExport(ctx, new QueryExecutor.RowHandler() {
                @Override
                public void onHeaders(List<QueryResultDto.Column> columns) {
                    String[] ks = new String[columns.size()];
                    for (int i = 0; i < ks.length; i++) ks[i] = columns.get(i).getKey();
                    keys[0] = ks;
                    Row r = sheet.createRow(rowIdx[0]++);
                    List<String> labels = ctx.headerLabels(columns);
                    for (int i = 0; i < labels.size(); i++) {
                        Cell c = r.createCell(i);
                        c.setCellValue(labels.get(i));
                        c.setCellStyle(headerStyle);
                    }
                }
                @Override
                public void onRow(List<Object> values) {
                    if (!ctx.allow(written[0])) return;
                    written[0]++;
                    QueryExport.writeXlsxRow(sheet, rowIdx[0]++, ctx.pick(values, keys[0]).toArray());
                }
            });

            try (OutputStream os = resp.getOutputStream()) {
                wb.write(os);
            }
        } finally {
            // 必须 dispose：SXSSF 的临时行文件不 dispose 会残留在磁盘上
            wb.dispose();
        }
        log.info("[report] export xlsx id={} rows={} capped={}", id, written[0], ctx.isCapped(written[0]));
    }

    /** 跑一次全量迭代（offset 恒 0，不分页），交给 handler 流式消费 */
    private void iterateExport(ExportCtx ctx, QueryExecutor.RowHandler handler) throws Exception {
        QueryService.Prepared p = ctx.prepared();
        executor.iterateRows(p.ds(), p.plain(), ctx.query().getSqlTemplate(),
                ctx.schema(), ctx.values(), 0L, handler);
    }

    /** Content-Disposition：ASCII 兜底文件名 + UTF-8 文件名（中文名要靠 filename*） */
    private static void setDisposition(HttpServletResponse resp, String fname) {
        String ascii = fname.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_");
        resp.setHeader("Content-Disposition",
                "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''"
                        + URLEncoder.encode(fname, StandardCharsets.UTF_8));
    }

    /** 文件名安全化：路径分隔符和控制字符会破坏 Content-Disposition / 落盘文件名 */
    private static String safeFileName(String name) {
        String s = (name == null || name.isBlank()) ? "report" : name.trim();
        s = s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        return s.isEmpty() ? "report" : s;
    }

    private List<ReportDto.ColumnCfg> parseColumns(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<ReportDto.ColumnCfg> list = MAPPER.readValue(json,
                    new TypeReference<List<ReportDto.ColumnCfg>>() {});
            return list == null ? List.of() : list;
        } catch (Exception ex) {
            log.warn("[report] columns 解析失败，按无列配置处理: {}", ex.getMessage());
            return List.of();
        }
    }

    /**
     * 导出上下文。
     *
     * <p>把列相关的两件事收在这里，因为它们必须看到**运行时**的列：
     * 报表的 {@code columns} 是「按 key 覆盖标签 / 标记隐藏」，不是「决定有哪些列」——
     * SQL 加了新列而报表没配过时，这一列必须照常导出（前端 mergeColumns 同理）。
     */
    private final class ExportCtx {
        private final ReportEntity report;
        private final QueryService.Prepared prepared;
        private final SysQuery query;
        private final List<QueryParamSchema> schema;
        private final Map<String, String> values;
        private final List<ReportDto.ColumnCfg> columns;
        private final long cap;
        private final Map<String, String> labelOverrides;
        private final Set<String> hiddenKeys;

        ExportCtx(ReportEntity report, QueryService.Prepared prepared, SysQuery query,
                  List<QueryParamSchema> schema, Map<String, String> values,
                  List<ReportDto.ColumnCfg> columns) {
            this.report = report;
            this.prepared = prepared;
            this.query = query;
            this.schema = schema;
            this.values = values;
            this.columns = columns;
            this.cap = report.getMaxRows() == null ? DEFAULT_MAX_ROWS : report.getMaxRows();

            Map<String, String> labels = new HashMap<>();
            Set<String> hidden = new HashSet<>();
            for (ReportDto.ColumnCfg c : columns) {
                if (c.getKey() == null) continue;
                if (Boolean.FALSE.equals(c.getVisible())) hidden.add(c.getKey());
                if (c.getLabel() != null && !c.getLabel().isBlank()) labels.put(c.getKey(), c.getLabel());
            }
            this.labelOverrides = labels;
            this.hiddenKeys = hidden;
        }

        ReportEntity report() { return report; }
        QueryService.Prepared prepared() { return prepared; }
        SysQuery query() { return query; }
        List<QueryParamSchema> schema() { return schema; }
        Map<String, String> values() { return values; }

        /** 护栏：cap<=0 表示不限。已达上限就丢弃后面的行 */
        boolean allow(long already) {
            return cap <= 0 || already < cap;
        }

        boolean isCapped(long written) {
            return cap > 0 && written >= cap;
        }

        /** 表头：优先用报表配置的列名（与屏幕所见一致） */
        List<String> headerLabels(List<QueryResultDto.Column> cols) {
            List<String> out = new ArrayList<>(cols.size());
            for (QueryResultDto.Column c : cols) {
                if (hiddenKeys.contains(c.getKey())) continue;
                out.add(labelOverrides.getOrDefault(c.getKey(), c.getLabel()));
            }
            return out;
        }

        /** 按 keys（表头列名）裁掉显式隐藏的列，values 与 keys 严格同序 */
        List<Object> pick(List<Object> values, String[] keys) {
            if (hiddenKeys.isEmpty() || keys == null || keys.length == 0) return values;
            List<Object> out = new ArrayList<>(values.size());
            for (int i = 0; i < values.size() && i < keys.length; i++) {
                if (!hiddenKeys.contains(keys[i])) out.add(values.get(i));
            }
            return out;
        }
    }
}
