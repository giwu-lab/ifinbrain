package com.anomalyco.ifinbrain.web.bi.service;

import com.anomalyco.ifinbrain.web.bi.dto.BiDashboardCreateReq;
import com.anomalyco.ifinbrain.web.bi.dto.BiDashboardDto;
import com.anomalyco.ifinbrain.web.bi.dto.BiDashboardUpdateReq;
import com.anomalyco.ifinbrain.web.bi.dto.DashboardLayout;
import com.anomalyco.ifinbrain.web.bi.entity.BiDashboardEntity;
import com.anomalyco.ifinbrain.web.bi.repository.BiDashboardRepository;
import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.common.PageReq;
import com.anomalyco.ifinbrain.web.common.PageResp;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

/** BI 大屏 CRUD + 发布态流转。权限位全是 {@code bi:dashboard:*}。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BiDashboardService {

    /** ObjectMapper 线程安全，建一次即可（layout_json 体积不小，别每次 new） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** code 复制时的后缀；base + "_copy" 超过 64 列时截断 base */
    private static final String COPY_SUFFIX = "_copy";
    private static final int CODE_MAX_LEN = 64;

    /** 列表页默认排序：sort 升序（轮播序），同 sort 时最近改的排前面 */
    private static final Sort DEFAULT_SORT = Sort.by(
            Sort.Order.asc("sort"), Sort.Order.desc("updatedAt"));

    private final BiDashboardRepository repo;
    private final PermissionChecker perm;

    // ===================== 查询 =====================

    /**
     * 列表页分页。
     *
     * <p>排序沿用改版前的全量查询：sort 升序（轮播序），同 sort 时最近改的排前面。
     * 用 Pageable 显式传进去，不能靠 Page 默认的主键序兜底。
     *
     * <p>每行都要解析 layout_json / theme_json（{@link #toDto}），全量返回时这里是列表页
     * 最重的一段；分页后单次最多解析 size 行，内存与响应体都有上界。
     */
    @Transactional(readOnly = true)
    public PageResp<BiDashboardDto> page(PageReq req) {
        perm.require("bi:dashboard:view");
        String kw = req.getKeyword();
        Page<BiDashboardEntity> p = (kw == null || kw.isBlank())
                ? repo.findAllBy(req.toPageable(DEFAULT_SORT))
                : repo.findByNameContainingIgnoreCaseOrCodeContainingIgnoreCaseOrDescriptionContainingIgnoreCase(
                        kw, kw, kw, req.toPageable(DEFAULT_SORT));
        return PageResp.of(p.getContent().stream().map(this::toDto).toList(),
                p.getTotalElements(), req.safePage(), req.safeSize());
    }

    /** 只看已发布（轮播 / 展示墙用） */
    @Transactional(readOnly = true)
    public List<BiDashboardDto> listPublished() {
        perm.require("bi:dashboard:view");
        return repo.findAllByStatusOrderBySortAsc((short) 1).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public BiDashboardDto detail(Long id) {
        perm.require("bi:dashboard:view");
        return toDto(require(id));
    }

    // ===================== 创建 =====================

    @Transactional
    public BiDashboardDto create(BiDashboardCreateReq req, String createdBy) {
        perm.require("bi:dashboard:create");
        String code = req.getCode().trim();
        if (repo.existsByCode(code)) {
            throw new BizException(400, "大屏编码已存在: " + code);
        }
        BiDashboardEntity e = BiDashboardEntity.builder()
                .code(code)
                .name(req.getName().trim())
                .description(req.getDescription())
                .layoutJson(writeLayout(req.getLayout()))
                .themeJson(writeTheme(req.getTheme()))
                .refreshInterval(req.getRefreshInterval() == null ? 0 : req.getRefreshInterval())
                .status((short) 0)
                .sort(0)
                .createdBy(createdBy == null ? "system" : createdBy)
                .build();
        return toDto(repo.save(e));
    }

    // ===================== 更新 =====================

    @Transactional
    public BiDashboardDto update(Long id, BiDashboardUpdateReq req) {
        perm.require("bi:dashboard:update");
        BiDashboardEntity e = require(id);

        if (req.getName() != null && !req.getName().isBlank()) {
            e.setName(req.getName().trim());
        }
        if (req.getDescription() != null) {
            e.setDescription(req.getDescription());
        }
        if (req.getLayout() != null) {
            e.setLayoutJson(writeLayout(req.getLayout()));
        }
        if (req.getTheme() != null) {
            e.setThemeJson(writeTheme(req.getTheme()));
        }
        if (req.getRefreshInterval() != null) {
            e.setRefreshInterval(req.getRefreshInterval());
        }
        if (req.getSort() != null) {
            e.setSort(req.getSort());
        }
        return toDto(repo.save(e));
    }

    // ===================== 复制 =====================

    /**
     * 复制大屏：新 code = 原 code + {@code _copy}，撞了就再叠一个 {@code _copy2}、{@code _copy3}…
     * 复制出来的一定是草稿（status=0、不带 published_at），排版/组件/绑定原样带走。
     */
    @Transactional
    public BiDashboardDto copy(Long id, String createdBy) {
        perm.require("bi:dashboard:create");
        BiDashboardEntity src = require(id);
        String code = nextCopyCode(src.getCode());

        BiDashboardEntity e = BiDashboardEntity.builder()
                .code(code)
                .name(nextCopyName(src.getName()))
                .description(src.getDescription())
                .layoutJson(src.getLayoutJson())
                .themeJson(src.getThemeJson())
                .refreshInterval(src.getRefreshInterval())
                .status((short) 0)
                .sort(src.getSort())
                .createdBy(createdBy == null ? "system" : createdBy)
                .build();
        return toDto(repo.save(e));
    }

    // ===================== 删除 =====================

    @Transactional
    public void delete(Long id) {
        perm.require("bi:dashboard:delete");
        repo.delete(require(id));
    }

    // ===================== 发布 / 反发布 =====================

    @Transactional
    public BiDashboardDto publish(Long id) {
        perm.require("bi:dashboard:publish");
        BiDashboardEntity e = require(id);
        e.setStatus((short) 1);
        e.setPublishedAt(LocalDateTime.now());
        return toDto(repo.save(e));
    }

    @Transactional
    public BiDashboardDto unpublish(Long id) {
        perm.require("bi:dashboard:publish");
        BiDashboardEntity e = require(id);
        e.setStatus((short) 0);
        return toDto(repo.save(e));
    }

    // ===================== 工具 =====================

    private BiDashboardEntity require(Long id) {
        return repo.findById(id).orElseThrow(() -> new BizException(404, "大屏不存在: " + id));
    }

    /**
     * Entity → DTO。
     *
     * <p>解析失败**降级**而不是 500：layout 是纯展示资产，坏掉的存量数据不该让整个列表页挂掉，
     * 前端拿到空画布至少还能进设计器重画。
     */
    private BiDashboardDto toDto(BiDashboardEntity e) {
        return BiDashboardDto.from(e, readLayout(e.getLayoutJson()), readTheme(e.getThemeJson()));
    }

    private DashboardLayout readLayout(String json) {
        if (json == null || json.isBlank()) return DashboardLayout.empty();
        try {
            DashboardLayout l = MAPPER.readValue(json, DashboardLayout.class);
            return l == null ? DashboardLayout.empty() : l;
        } catch (Exception ex) {
            log.warn("[bi] layout_json 解析失败，降级为空画布: {}", ex.getMessage());
            return DashboardLayout.empty();
        }
    }

    private DashboardLayout.Theme readTheme(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, DashboardLayout.Theme.class);
        } catch (Exception ex) {
            log.warn("[bi] theme_json 解析失败，降级为跟随 layout.theme: {}", ex.getMessage());
            return null;
        }
    }

    /** 落库前序列化；失败是「入参不可用」，回 400 让设计器当场改 */
    private String writeLayout(DashboardLayout layout) {
        try {
            return MAPPER.writeValueAsString(layout == null ? DashboardLayout.empty() : layout);
        } catch (Exception ex) {
            throw new BizException(400, "大屏布局序列化失败，无法保存: " + ex.getMessage());
        }
    }

    private String writeTheme(DashboardLayout.Theme theme) {
        if (theme == null) return null;
        try {
            return MAPPER.writeValueAsString(theme);
        } catch (Exception ex) {
            throw new BizException(400, "大屏主题序列化失败，无法保存: " + ex.getMessage());
        }
    }

    /** 原 code 加 _copy；已存在则叠加 _copy2、_copy3…（seq 上限兜底，防脏数据导致死循环） */
    private String nextCopyCode(String base) {
        String root = base == null ? "dashboard" : base;
        // base + "_copy" 必须落在 VARCHAR(64) 内，否则从尾部截 base
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

    private static String nextCopyName(String name) {
        String n = name == null ? "大屏" : name;
        return n + " - 副本";
    }
}
