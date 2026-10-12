package com.anomalyco.ifinbrain.web.bi.service;

import com.anomalyco.ifinbrain.web.bi.dto.BiDashboardCreateReq;
import com.anomalyco.ifinbrain.web.bi.dto.BiDashboardDto;
import com.anomalyco.ifinbrain.web.bi.dto.BiDashboardUpdateReq;
import com.anomalyco.ifinbrain.web.bi.dto.DashboardLayout;
import com.anomalyco.ifinbrain.web.bi.entity.BiDashboardEntity;
import com.anomalyco.ifinbrain.web.bi.repository.BiDashboardRepository;
import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.common.EntityAuthSupport;
import com.anomalyco.ifinbrain.web.common.MenuPublisher;
import com.anomalyco.ifinbrain.web.common.PageReq;
import com.anomalyco.ifinbrain.web.common.PageResp;
import com.anomalyco.ifinbrain.web.common.PublishMenuReq;
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
import java.util.Map;
import java.util.Set;

/** BI 大屏 CRUD + 发布态流转。
 *
 * <p><b>两级权限</b>：模块级 {@code bi:dashboard:view/create/update/delete/publish}
 * 管「能不能进这个模块、能做哪类操作」；实体级 {@code bi:dashboard:{code}:{verb}}
 * 管「这一张大屏能不能看/改/发布/删」。查看权由实体级取代模块级 ——
 * 只要某张大屏的 view 给了你，列表和播放页就能进，不要求先有模块级 view。
 * 其余三个动作两层都要过。
 *
 * <p>实体的稳定标识用 {@code code} 而不是自增 id：id 会随数据迁移变化，
 * 权限码变了等于所有授权一夜蒸发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BiDashboardService {

    /** 实体级权限码前缀 */
    public static final String PERM_PREFIX = "bi:dashboard";

    /** ObjectMapper 线程安全，建一次即可（layout_json 体积不小，别每次 new） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** code 复制时的后缀；base + "_copy" 超过 64 列时截断 base */
    private static final String COPY_SUFFIX = "_copy";
    private static final int CODE_MAX_LEN = 64;

    /** 列表页默认排序：sort 升序（轮播序），同 sort 时最近改的排前面 */
    private static final Sort DEFAULT_SORT = Sort.by(
            Sort.Order.asc("sort"), Sort.Order.desc("updatedAt"));

    private final BiDashboardRepository repo;
    private final MenuPublisher menuPublisher;
    private final PermissionChecker perm;
    private final EntityAuthSupport auth;

    // ===================== 查询 =====================

    /**
     * 列表页分页。
     *
     * <p>排序沿用改版前的全量查询：sort 升序（轮播序），同 sort 时最近改的排前面。
     * 用 Pageable 显式传进去，不能靠 Page 默认的主键序兜底。
     *
     * <p>每行都要解析 layout_json / theme_json（{@link #toDto}），全量返回时这里是列表页
     * 最重的一段；分页后单次最多解析 size 行，内存与响应体都有上界。
     *
     * <p><b>实体级过滤下推给 DB</b>：没有 {@code :view} 授权的 code 集合会在
     * 查询前从权限里反推出来，用 {@code In} 交给数据库。放在内存里逐行过滤的话
     * 分页就名存实亡了（先分页再过滤 → 每页行数忽多忽少，末页还会漏）。
     */
    @Transactional(readOnly = true)
    public PageResp<BiDashboardDto> page(PageReq req) {
        Set<String> allowed = auth.allowedCodes(PERM_PREFIX, "view");
        if (allowed.isEmpty()) return PageResp.of(List.of(), 0, req.safePage(), req.safeSize());
        String kw = req.getKeyword();
        Page<BiDashboardEntity> p = (kw == null || kw.isBlank())
                ? repo.findAllByCodeIn(allowed, req.toPageable(DEFAULT_SORT))
                : repo.findByNameContainingIgnoreCaseOrCodeContainingIgnoreCaseOrDescriptionContainingIgnoreCaseAndCodeIn(
                        kw, kw, kw, allowed, req.toPageable(DEFAULT_SORT));
        return PageResp.of(p.getContent().stream().map(this::toDto).toList(),
                p.getTotalElements(), req.safePage(), req.safeSize());
    }

    /** 只看已发布（轮播 / 展示墙用） */
    @Transactional(readOnly = true)
    public List<BiDashboardDto> listPublished() {
        Set<String> allowed = auth.allowedCodes(PERM_PREFIX, "view");
        if (allowed.isEmpty()) return List.of();
        return repo.findAllByStatusAndCodeInOrderBySortAsc((short) 1, allowed).stream().map(this::toDto).toList();
    }

    /** 详情：实体级 view —— 非授权用户直接探测 id 会被挡在 403 */
    @Transactional(readOnly = true)
    public BiDashboardDto detail(Long id) {
        BiDashboardEntity e = require(id);
        auth.require(PERM_PREFIX, e.getCode(), "view");
        return toDto(e);
    }

    /** 设计器读取大屏：改设计的人也要有 view 之外的东西，这里先按 design 判 */
    @Transactional(readOnly = true)
    public BiDashboardDto detailForDesign(Long id) {
        BiDashboardEntity e = require(id);
        auth.require(PERM_PREFIX, e.getCode(), "design");
        return toDto(e);
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
        BiDashboardEntity saved = repo.save(e);
        // 建实体 = 注册 4 个实体级权限码 + seed 给 admin（只有 admin，不给 operator/viewer）
        auth.onEntityCreated(PERM_PREFIX, code, saved.getName());
        log.info("[bi] created dashboard id={} code={} 4 perms + admin seeded", saved.getId(), code);
        return toDto(saved);
    }

    // ===================== 更新 =====================

    @Transactional
    public BiDashboardDto update(Long id, BiDashboardUpdateReq req) {
        perm.require("bi:dashboard:update");
        BiDashboardEntity e = require(id);
        auth.require(PERM_PREFIX, e.getCode(), "design");

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
        // 复制需要先能看原件，否则等于给了 create 权限就能把别人的大屏内容原样搬走
        auth.require(PERM_PREFIX, src.getCode(), "view");
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
        BiDashboardEntity copy = repo.save(e);
        auth.onEntityCreated(PERM_PREFIX, code, copy.getName());
        return toDto(copy);
    }

    // ===================== 删除 =====================

    @Transactional
    public void delete(Long id) {
        perm.require("bi:dashboard:delete");
        BiDashboardEntity e = require(id);
        auth.require(PERM_PREFIX, e.getCode(), "delete");
        // 菜单一并下线：大屏都没了，留个指向 404 的侧边栏入口只会让人以为系统坏了
        menuPublisher.unpublish(menuPath(id));
        repo.delete(e);
        // 权限码一并清掉。留着会变成「幽灵授权」：code 复用（删了再建同名）
        // 时新大屏会白捡一份旧授权。
        auth.revokeEntity(PERM_PREFIX, e.getCode());
    }

    // ===================== 发布 / 反发布 =====================

    /**
     * 发布：翻 status 位<b>并</b>在菜单树里建入口。
     *
     * <p>两件事必须同时做，因为它们管的是两拨人：
     * <ul>
     *   <li>{@code status=1} —— 决定能不能进轮播（展示墙靠 {@link #listPublished()} 取数），
     *       也就是「这台大屏现在算不算上线」；</li>
     *   <li>菜单行 —— 决定侧边栏有没有入口，也是别人能不能找到它的唯一途径。
     *       只翻 status 的话，已发布的大屏除了设计器没人找得到。</li>
     * </ul>
     *
     * <p>建 / 改 / 软删交给 {@link MenuPublisher}，与报表共用同一套逻辑 ——
     * 两个模块的发布语义必须一模一样，否则用户会遇到「报表能选目录、大屏不能」。
     *
     * @param body 可空；字段留空 = 沿用上次的目录 / 显示名 / 排序
     */
    @Transactional
    public BiDashboardDto publish(Long id, PublishMenuReq body) {
        perm.require("bi:dashboard:publish");
        BiDashboardEntity e = require(id);
        auth.require(PERM_PREFIX, e.getCode(), "publish");
        // 菜单行的 permission 改成实体级 view —— 侧边栏入口跟着实体授权走，
        // 收回某张大屏的 view，入口对没权限的角色自然消失
        menuPublisher.publish(menuPath(id), auth.permCode(PERM_PREFIX, e.getCode(), "view"), body, e.getName());
        e.setStatus((short) 1);
        e.setPublishedAt(LocalDateTime.now());
        return toDto(repo.save(e));
    }

    /**
     * 取消发布：退回草稿（同时退出轮播）+ 菜单软下线（status=0，可重新发布恢复）。
     *
     * <p>菜单走软删而不是删行：用户调好的目录 / 显示名 / 排序得留着，
     * 重新发布时不该被无声地弹回默认值。
     */
    @Transactional
    public BiDashboardDto unpublish(Long id) {
        perm.require("bi:dashboard:publish");
        BiDashboardEntity e = require(id);
        auth.require(PERM_PREFIX, e.getCode(), "publish");
        menuPublisher.unpublish(menuPath(id));
        e.setStatus((short) 0);
        return toDto(repo.save(e));
    }

    // ===================== 实体级授权 =====================

    /** 当前这张大屏的授权：{view:[roleId], design:[...], publish:[...], delete:[...]} */
    @Transactional(readOnly = true)
    public Map<String, List<Long>> getPermissions(Long id) {
        perm.require("bi:dashboard:update");
        return auth.getPermissions(PERM_PREFIX, require(id).getCode());
    }

    /**
     * 覆盖式写入授权。
     *
     * <p>门槛用 {@code bi:dashboard:update} 而不是「持有这张大屏的 update」——
     * 否则一个只能编辑自己那几张大屏的人，给别人建的大屏授权不了，
     * 而这恰恰是管理员最需要做的事。
     */
    @Transactional
    public void updatePermissions(Long id, Map<String, List<Long>> grants) {
        perm.require("bi:dashboard:update");
        String code = require(id).getCode();
        auth.updatePermissions(PERM_PREFIX, code, grants);
        log.info("[bi] updated permissions for dashboard id={} code={}", id, code);
    }

    /** 可选父目录列表（发布对话框的下拉） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> publishDirs() {
        perm.require("bi:dashboard:publish");
        return menuPublisher.dirs();
    }

    /**
     * 菜单 path = /bi/dashboard/view/{dashboardId}。
     *
     * <p>与播放页路由同形 —— 既是反查菜单的依据，也是一条稳定可收藏的直链。
     */
    private static String menuPath(Long dashboardId) {
        return "/bi/dashboard/view/" + dashboardId;
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
