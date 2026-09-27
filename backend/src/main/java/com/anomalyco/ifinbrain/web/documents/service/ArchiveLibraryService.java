package com.anomalyco.ifinbrain.web.documents.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.documents.dto.ArchiveLibraryCreateReq;
import com.anomalyco.ifinbrain.web.documents.dto.ArchiveLibraryDto;
import com.anomalyco.ifinbrain.web.documents.dto.ArchiveLibraryUpdateReq;
import com.anomalyco.ifinbrain.web.documents.entity.ArchiveLibrary;
import com.anomalyco.ifinbrain.web.documents.repository.ArchiveLibraryRepository;
import com.anomalyco.ifinbrain.web.documents.repository.DocumentRecordRepository;
import com.anomalyco.ifinbrain.web.entity.SysMenu;
import com.anomalyco.ifinbrain.web.entity.SysPermission;
import com.anomalyco.ifinbrain.web.entity.SysRole;
import com.anomalyco.ifinbrain.web.entity.SysRolePerm;
import com.anomalyco.ifinbrain.web.repository.SysMenuRepository;
import com.anomalyco.ifinbrain.web.repository.SysPermissionRepository;
import com.anomalyco.ifinbrain.web.repository.SysRolePermRepository;
import com.anomalyco.ifinbrain.web.repository.SysRoleRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.anomalyco.ifinbrain.web.storage.StorageSelector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 档案库（原「类目」）Service。
 * 建/改/删档案库时同步维护 sys_menu 子菜单 + 4 个 perm code + admin 角色绑定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArchiveLibraryService {

    /** V17 顶级菜单 id（Documents DIR）。建/改/删 档案库时同步维护其下子菜单。 */
    private static final Long DOCUMENTS_DIR_ID = 50L;

    /** 所有档案库共用同一前端组件。/documents/:slug 静态 catch-all 路由统一接管。 */
    private static final String LIBRARY_COMPONENT = "documents/list/Index";

    /** 档案库下的 4 个权限 verb —— 与文档 CRUD 一一对应 */
    private static final String[] CAT_PERMS = { "view", "upload", "update", "delete" };

    /** 4 个 verb 的中文显示名（用于 sys_permission.name；前端授权对话框不依赖这个字段） */
    private static final java.util.Map<String, String> VERB_ZH = java.util.Map.of(
            "view", "查看",
            "upload", "上传",
            "update", "修改",
            "delete", "删除"
    );

    /** admin 角色 code —— 新建/迁移/重命名 档案库时自动 seed 4 权限 */
    private static final String ADMIN_CODE = "admin";

    private final ArchiveLibraryRepository libraryRepo;
    private final DocumentRecordRepository documentRepo;
    private final SysMenuRepository menuRepo;
    private final SysPermissionRepository permRepo;
    private final SysRolePermRepository rolePermRepo;
    private final SysRoleRepository roleRepo;
    private final StorageSelector storage;
    private final PermissionChecker perm;

    // ===================== 查询 =====================

    @Transactional(readOnly = true)
    public List<ArchiveLibraryDto> list() {
        // 只返回当前用户拥有 documents:{slug}:view 的档案库 —— 防直接 URL 探测
        // 不强求全局 documents:view —— 让仅有档案库级权限的用户（如 demo 测试角色）也能拿到自己的档案库列表
        return libraryRepo.findAllByStatusOrderBySortAscIdAsc((short) 1).stream()
                .filter(c -> perm.has("documents:" + c.getSlug() + ":view"))
                .map(c -> ArchiveLibraryDto.fromEntity(c, fileCount(c.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ArchiveLibraryDto detail(Long id) {
        ArchiveLibrary e = libraryRepo.findById(id)
                .orElseThrow(() -> new BizException(404, "档案库不存在"));
        // 档案库级 view —— 非授权用户探测 id 直接 403
        String permCode = "documents:" + e.getSlug() + ":view";
        if (!perm.has(permCode)) {
            throw new BizException(403, "无权访问该档案库（" + permCode + "）");
        }
        return ArchiveLibraryDto.fromEntity(e, fileCount(id));
    }

    @Transactional(readOnly = true)
    public ArchiveLibraryDto detailBySlug(String slug) {
        ArchiveLibrary e = libraryRepo.findBySlug(slug)
                .orElseThrow(() -> new BizException(404, "档案库不存在"));
        // 档案库级 view —— 非授权用户探测直接 403
        String permCode = "documents:" + slug + ":view";
        if (!perm.has(permCode)) {
            throw new BizException(403, "无权访问该档案库（" + permCode + "）");
        }
        return ArchiveLibraryDto.fromEntity(e, fileCount(e.getId()));
    }

    private long fileCount(Long libraryId) {
        return documentRepo.countByCategoryIdAndStatus(libraryId, (short) 1);
    }

    // ===================== 创建 =====================

    @Transactional
    public ArchiveLibraryDto create(ArchiveLibraryCreateReq req) {
        perm.require("documents:upload");

        String slug = req.getSlug().trim().toLowerCase();
        if (libraryRepo.existsBySlug(slug)) {
            throw new BizException(400, "档案库 slug 已存在: " + slug);
        }

        ArchiveLibrary e = ArchiveLibrary.builder()
                .name(req.getName().trim())
                .slug(slug)
                .icon(iconOrDefault(req.getIcon()))
                .sort(req.getSort() != null ? req.getSort() : nextSort())
                .status(req.getStatus() != null ? req.getStatus() : (short) 1)
                .remark(req.getRemark())
                .createdBy(perm.currentUserId())
                .build();
        e = libraryRepo.save(e);

        // 1. 注册 4 个 perm code（已存在则跳过，幂等）
        ensureLibraryPerms(e.getSlug(), e.getName());

        // 2. 同步插入 sys_menu 子菜单行 —— permission 用档案库专属 code
        SysMenu menu = SysMenu.builder()
                .parentId(DOCUMENTS_DIR_ID)
                .name(e.getName())
                .path("/documents/" + e.getSlug())
                .component(LIBRARY_COMPONENT)
                .icon(e.getIcon())
                .sort(e.getSort())
                .permission(permCode(slug, "view"))
                .type("MENU")
                .status(e.getStatus())
                .build();
        menuRepo.save(menu);

        // 3. 给 admin 角色 seed 4 个 perm（operator/viewer 不 seed —— 由 admin 通过「权限」按钮显式分配）
        seedAdminAccess(slug);

        // 4. storage 上建立档案库目录
        storage.current().ensurePrefix("documents/" + e.getSlug());

        log.info("[documents] created archive library id={} slug={} 4perms+admin-seeded", e.getId(), e.getSlug());
        return ArchiveLibraryDto.fromEntity(e, 0L);
    }

    // ===================== 更新 =====================

    @Transactional
    public ArchiveLibraryDto update(Long id, ArchiveLibraryUpdateReq req) {
        perm.require("documents:update");
        ArchiveLibrary e = libraryRepo.findById(id)
                .orElseThrow(() -> new BizException(404, "档案库不存在"));

        String oldSlug = e.getSlug();
        String oldPath = "/documents/" + oldSlug;
        boolean slugChanged = false;

        if (req.getSlug() != null && !req.getSlug().isBlank()) {
            String newSlug = req.getSlug().trim().toLowerCase();
            if (!newSlug.equals(e.getSlug())) {
                if (libraryRepo.existsBySlug(newSlug)) {
                    throw new BizException(400, "档案库 slug 已存在: " + newSlug);
                }
                e.setSlug(newSlug);
                slugChanged = true;
            }
        }
        if (req.getName() != null && !req.getName().isBlank()) {
            e.setName(req.getName().trim());
        }
        if (req.getIcon() != null) {
            e.setIcon(iconOrDefault(req.getIcon()));
        }
        if (req.getSort() != null) e.setSort(req.getSort());
        if (req.getRemark() != null) e.setRemark(req.getRemark());
        if (req.getStatus() != null) e.setStatus(req.getStatus());
        final ArchiveLibrary saved = libraryRepo.save(e);

        // 同步更新 sys_menu 行（按 OLD path + component 双重匹配，避免误命中「档案库」系统菜单）
        menuRepo.findAll().stream()
                .filter(m -> DOCUMENTS_DIR_ID.equals(m.getParentId()))
                .filter(m -> oldPath.equals(m.getPath()))
                .filter(m -> LIBRARY_COMPONENT.equals(m.getComponent()))
                .findFirst()
                .ifPresent(m -> {
                    m.setName(saved.getName());
                    m.setPath("/documents/" + saved.getSlug());
                    m.setIcon(saved.getIcon());
                    m.setSort(saved.getSort());
                    m.setStatus(saved.getStatus());
                    m.setPermission(permCode(saved.getSlug(), "view"));
                    menuRepo.save(m);
                    log.info("[documents] synced menu id={} path={} -> {}", m.getId(), oldPath, m.getPath());
                });

        // slug 改名：旧 perm code 全部解绑 + 新 perm code 注册 + admin 重新 seed
        if (slugChanged) {
            migratePermsForSlugRename(oldSlug, saved.getSlug(), saved.getName());
        }

        return ArchiveLibraryDto.fromEntity(saved, fileCount(saved.getId()));
    }

    // ===================== 删除 =====================

    @Transactional
    public void delete(Long id) {
        perm.require("documents:delete");
        ArchiveLibrary e = libraryRepo.findById(id)
                .orElseThrow(() -> new BizException(404, "档案库不存在"));

        long count = documentRepo.countByCategoryIdAndStatus(id, (short) 1);
        if (count > 0) {
            throw new BizException(400,
                    "档案库「" + e.getName() + "」下仍有 " + count + " 个档案，请先处理后再删除");
        }

        // 删档案库 + 同步删对应 sys_menu 行
        libraryRepo.delete(e);
        String targetPath = "/documents/" + e.getSlug();
        menuRepo.findAll().stream()
                .filter(m -> DOCUMENTS_DIR_ID.equals(m.getParentId()))
                .filter(m -> targetPath.equals(m.getPath()))
                .filter(m -> LIBRARY_COMPONENT.equals(m.getComponent()))
                .findFirst()
                .ifPresent(m -> {
                    menuRepo.delete(m);
                    log.info("[documents] deleted menu id={} path={}", m.getId(), targetPath);
                });

        // 4 个 perm code 全部解绑所有角色绑定 + 删 sys_permission 行本身。
        // 不能保留 —— perm.name 字段含被删档案库名，留着会污染角色列表（admin 仍显示「拥有销售部/工程部」等孤儿授权）。
        // ensureLibraryPerms() 用 existsByCode 做幂等检查，slug 将来被复用时会自动重建。
        unbindAndDeleteLibraryPerms(e.getSlug());

        // 清理 storage 上对应的档案库目录（非空时跳过；S3 上为 no-op）
        storage.current().deletePrefix("documents/" + e.getSlug());

        log.info("[documents] deleted archive library id={} slug={}", id, e.getSlug());
    }

    // ===================== 授权 =====================

    /**
     * 查询档案库 4 个 perm 当前绑定到哪些角色。
     * 返回 {view: [roleIds], upload: [roleIds], update: [roleIds], delete: [roleIds]}
     */
    @Transactional(readOnly = true)
    public Map<String, List<Long>> getPermissions(Long id) {
        perm.require("documents:upload");
        ArchiveLibrary e = libraryRepo.findById(id)
                .orElseThrow(() -> new BizException(404, "档案库不存在"));

        Map<String, List<Long>> result = new java.util.LinkedHashMap<>();
        for (String verb : CAT_PERMS) {
            String code = permCode(e.getSlug(), verb);
            Optional<SysPermission> p = permRepo.findByCode(code);
            if (p.isEmpty()) {
                result.put(verb, List.of());
                continue;
            }
            List<Long> roleIds = rolePermRepo.findAll().stream()
                    .filter(rp -> rp.getPermId().equals(p.get().getId()))
                    .map(SysRolePerm::getRoleId)
                    .toList();
            result.put(verb, roleIds);
        }
        return result;
    }

    /**
     * 全量替换档案库 4 个 perm 的角色绑定。
     * 输入 {view: [roleIds], upload: [roleIds], ...} —— 空列表 = 收回所有授权（除 admin）。
     * 注意：admin 角色的 4 个绑定永远保留（避免 admin 自我锁死）。
     */
    @Transactional
    public void updatePermissions(Long id, Map<String, List<Long>> grants) {
        perm.require("documents:upload");
        ArchiveLibrary e = libraryRepo.findById(id)
                .orElseThrow(() -> new BizException(404, "档案库不存在"));

        Long adminId = roleRepo.findByCode(ADMIN_CODE)
                .orElseThrow(() -> new BizException(500, "admin 角色缺失"))
                .getId();

        for (String verb : CAT_PERMS) {
            String code = permCode(e.getSlug(), verb);
            SysPermission p = permRepo.findByCode(code)
                    .orElseThrow(() -> new BizException(500, "perm 缺失: " + code));

            // 解析入参
            List<Long> requested = grants == null ? List.of()
                    : grants.getOrDefault(verb, List.of());
            // admin 强制保留（即使入参里没勾）
            List<Long> finalRoleIds = new ArrayList<>(requested);
            if (!finalRoleIds.contains(adminId)) finalRoleIds.add(adminId);

            // 当前已绑定的角色
            List<Long> currentRoleIds = rolePermRepo.findAll().stream()
                    .filter(rp -> rp.getPermId().equals(p.getId()))
                    .map(SysRolePerm::getRoleId)
                    .toList();

            // 差集：需要新增的、需删除的
            List<Long> toAdd = finalRoleIds.stream().filter(r -> !currentRoleIds.contains(r)).toList();
            List<Long> toRemove = currentRoleIds.stream().filter(r -> !finalRoleIds.contains(r)).toList();

            for (Long rid : toAdd) {
                rolePermRepo.save(SysRolePerm.builder().roleId(rid).permId(p.getId()).build());
            }
            for (Long rid : toRemove) {
                rolePermRepo.deleteByRoleIdAndPermId(rid, p.getId());
            }
        }
        rolePermRepo.flush();
        log.info("[documents] updated permissions for archive library id={} slug={}", id, e.getSlug());
    }

    // ===================== helpers =====================

    private String iconOrDefault(String icon) {
        return (icon == null || icon.isBlank()) ? "Folder" : icon.trim();
    }

    private Integer nextSort() {
        return libraryRepo.findAll().stream()
                .map(ArchiveLibrary::getSort)
                .filter(s -> s != null)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    /** 拼档案库权限码：documents:{slug}:{verb} */
    private static String permCode(String slug, String verb) {
        return "documents:" + slug + ":" + verb;
    }

    /** 档案库权限码前缀：documents:{slug} （用于批量查询/解绑） */
    private static String permPrefix(String slug) {
        return "documents:" + slug + ":";
    }

    /** 注册 4 个 perm code（已存在则跳过，幂等） */
    private void ensureLibraryPerms(String slug, String libraryName) {
        for (String verb : CAT_PERMS) {
            String code = permCode(slug, verb);
            if (!permRepo.existsByCode(code)) {
                permRepo.save(SysPermission.builder()
                        .code(code)
                        .name(libraryName + " 档案库" + VERB_ZH.get(verb))
                        .type("API")
                        .build());
            }
        }
    }

    /** 给 admin 角色 seed 4 个 perm —— operator/viewer 不 seed */
    private void seedAdminAccess(String slug) {
        SysRole admin = roleRepo.findByCode(ADMIN_CODE)
                .orElseThrow(() -> new BizException(500, "admin 角色缺失"));
        for (String verb : CAT_PERMS) {
            SysPermission p = permRepo.findByCode(permCode(slug, verb))
                    .orElseThrow(() -> new BizException(500, "perm 缺失: " + permCode(slug, verb)));
            if (!rolePermRepo.existsByRoleIdAndPermId(admin.getId(), p.getId())) {
                rolePermRepo.save(SysRolePerm.builder()
                        .roleId(admin.getId())
                        .permId(p.getId())
                        .build());
            }
        }
    }

    /** slug 改名：旧 perm code 的所有 sys_role_perm 绑定删除；新 perm code 已注册 + admin 重新 seed */
    private void migratePermsForSlugRename(String oldSlug, String newSlug, String newName) {
        List<SysPermission> oldPerms = permRepo.findByCodeStartingWith(permPrefix(oldSlug));
        for (SysPermission p : oldPerms) {
            rolePermRepo.deleteByPermId(p.getId());
        }
        rolePermRepo.flush();
        ensureLibraryPerms(newSlug, newName);
        seedAdminAccess(newSlug);
        log.info("[documents] migrated perms: slug {} -> {} ({} old perms unbound)", oldSlug, newSlug, oldPerms.size());
    }

    /** 档案库删除时：4 个 perm code 的所有角色绑定删除 + 删 sys_permission 行本身。
     *  不能保留 perm 行 —— perm.name 字段含被删档案库名，留着会污染角色列表 UI。 */
    private void unbindAndDeleteLibraryPerms(String slug) {
        List<SysPermission> perms = permRepo.findByCodeStartingWith(permPrefix(slug));
        for (SysPermission p : perms) {
            rolePermRepo.deleteByPermId(p.getId());
            permRepo.delete(p);
        }
        rolePermRepo.flush();
        permRepo.flush();
        log.info("[documents] unbound + deleted {} perms for deleted slug={}", perms.size(), slug);
    }
}
