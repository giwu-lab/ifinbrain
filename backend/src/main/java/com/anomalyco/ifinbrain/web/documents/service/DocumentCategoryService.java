package com.anomalyco.ifinbrain.web.documents.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.documents.dto.DocumentCategoryCreateReq;
import com.anomalyco.ifinbrain.web.documents.dto.DocumentCategoryDto;
import com.anomalyco.ifinbrain.web.documents.dto.DocumentCategoryUpdateReq;
import com.anomalyco.ifinbrain.web.documents.entity.DocumentCategory;
import com.anomalyco.ifinbrain.web.documents.repository.DocumentCategoryRepository;
import com.anomalyco.ifinbrain.web.documents.repository.DocumentRecordRepository;
import com.anomalyco.ifinbrain.web.entity.SysMenu;
import com.anomalyco.ifinbrain.web.repository.SysMenuRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.anomalyco.ifinbrain.web.storage.StorageSelector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentCategoryService {

    /** V17 顶级菜单 id（Documents DIR）。建/改/删 类目时同步维护其下子菜单。 */
    private static final Long DOCUMENTS_DIR_ID = 50L;

    /** 所有类目共用同一前端组件。P6 会在路由层把这个 component key 替换成静态 `/documents/:slug`。 */
    private static final String CATEGORY_COMPONENT = "documents/list/Index";

    private final DocumentCategoryRepository categoryRepo;
    private final DocumentRecordRepository documentRepo;
    private final SysMenuRepository menuRepo;
    private final StorageSelector storage;
    private final PermissionChecker perm;

    // ===================== 查询 =====================

    @Transactional(readOnly = true)
    public List<DocumentCategoryDto> list() {
        perm.require("documents:view");
        return categoryRepo.findAllByStatusOrderBySortAscIdAsc((short) 1).stream()
                .map(c -> DocumentCategoryDto.fromEntity(c, fileCount(c.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentCategoryDto detail(Long id) {
        perm.require("documents:view");
        return categoryRepo.findById(id)
                .map(c -> DocumentCategoryDto.fromEntity(c, fileCount(id)))
                .orElseThrow(() -> new BizException(404, "类目不存在"));
    }

    @Transactional(readOnly = true)
    public DocumentCategoryDto detailBySlug(String slug) {
        perm.require("documents:view");
        return categoryRepo.findBySlug(slug)
                .map(c -> DocumentCategoryDto.fromEntity(c, fileCount(c.getId())))
                .orElseThrow(() -> new BizException(404, "类目不存在"));
    }

    private long fileCount(Long categoryId) {
        return documentRepo.countByCategoryIdAndStatus(categoryId, (short) 1);
    }

    // ===================== 创建 =====================

    @Transactional
    public DocumentCategoryDto create(DocumentCategoryCreateReq req) {
        perm.require("documents:upload");

        String slug = req.getSlug().trim().toLowerCase();
        if (categoryRepo.existsBySlug(slug)) {
            throw new BizException(400, "类目 slug 已存在: " + slug);
        }

        DocumentCategory e = DocumentCategory.builder()
                .name(req.getName().trim())
                .slug(slug)
                .icon(iconOrDefault(req.getIcon()))
                .sort(req.getSort() != null ? req.getSort() : nextSort())
                .status(req.getStatus() != null ? req.getStatus() : (short) 1)
                .remark(req.getRemark())
                .createdBy(perm.currentUserId())
                .build();
        e = categoryRepo.save(e);

        // 同步插入 sys_menu 子菜单行
        SysMenu menu = SysMenu.builder()
                .parentId(DOCUMENTS_DIR_ID)
                .name(e.getName())
                .path("/documents/" + e.getSlug())
                .component(CATEGORY_COMPONENT)
                .icon(e.getIcon())
                .sort(e.getSort())
                .permission("documents:view")
                .type("MENU")
                .status(e.getStatus())
                .build();
        menuRepo.save(menu);

        // 在 storage 上建立对应的类目目录
        // - local: mkdir -p documents/{slug}/
        // - s3   : no-op（key prefix 首次 put 时隐式创建）
        storage.current().ensurePrefix("documents/" + e.getSlug());

        log.info("[documents] created category id={} slug={} menu=created dir=ensured", e.getId(), e.getSlug());
        return DocumentCategoryDto.fromEntity(e, 0L);
    }

    // ===================== 更新 =====================

    @Transactional
    public DocumentCategoryDto update(Long id, DocumentCategoryUpdateReq req) {
        perm.require("documents:update");
        DocumentCategory e = categoryRepo.findById(id)
                .orElseThrow(() -> new BizException(404, "类目不存在"));

        String oldSlug = e.getSlug();
        String oldPath = "/documents/" + oldSlug;

        if (req.getSlug() != null && !req.getSlug().isBlank()) {
            String newSlug = req.getSlug().trim().toLowerCase();
            if (!newSlug.equals(e.getSlug()) && categoryRepo.existsBySlug(newSlug)) {
                throw new BizException(400, "类目 slug 已存在: " + newSlug);
            }
            e.setSlug(newSlug);
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
        final DocumentCategory saved = categoryRepo.save(e);

        // 同步更新 sys_menu 行（按 OLD path + component 双重匹配，避免误命中"类目设置"系统菜单）
        menuRepo.findAll().stream()
                .filter(m -> DOCUMENTS_DIR_ID.equals(m.getParentId()))
                .filter(m -> oldPath.equals(m.getPath()))
                .filter(m -> CATEGORY_COMPONENT.equals(m.getComponent()))
                .findFirst()
                .ifPresent(m -> {
                    m.setName(saved.getName());
                    m.setPath("/documents/" + saved.getSlug());
                    m.setIcon(saved.getIcon());
                    m.setSort(saved.getSort());
                    m.setStatus(saved.getStatus());
                    menuRepo.save(m);
                    log.info("[documents] synced menu id={} path={} -> {}", m.getId(), oldPath, m.getPath());
                });

        return DocumentCategoryDto.fromEntity(saved, fileCount(saved.getId()));
    }

    // ===================== 删除 =====================

    @Transactional
    public void delete(Long id) {
        perm.require("documents:delete");
        DocumentCategory e = categoryRepo.findById(id)
                .orElseThrow(() -> new BizException(404, "类目不存在"));

        long count = documentRepo.countByCategoryIdAndStatus(id, (short) 1);
        if (count > 0) {
            throw new BizException(400,
                    "类目「" + e.getName() + "」下仍有 " + count + " 个档案，请先处理后再删除");
        }

        // 删类目 + 同步删对应 sys_menu 行
        // 关键：必须按 component=CATEGORY_COMPONENT 双重匹配，避免误删"类目设置"系统菜单
        // （如果用户曾创建 slug="category"，path 也会是 /documents/category）
        categoryRepo.delete(e);
        String targetPath = "/documents/" + e.getSlug();
        menuRepo.findAll().stream()
                .filter(m -> DOCUMENTS_DIR_ID.equals(m.getParentId()))
                .filter(m -> targetPath.equals(m.getPath()))
                .filter(m -> CATEGORY_COMPONENT.equals(m.getComponent()))
                .findFirst()
                .ifPresent(m -> {
                    menuRepo.delete(m);
                    log.info("[documents] deleted menu id={} path={}", m.getId(), targetPath);
                });

        // 清理 storage 上对应的类目目录（非空时跳过；S3 上为 no-op）
        storage.current().deletePrefix("documents/" + e.getSlug());

        log.info("[documents] deleted category id={} slug={}", id, e.getSlug());
    }

    // ===================== helpers =====================

    private String iconOrDefault(String icon) {
        return (icon == null || icon.isBlank()) ? "Folder" : icon.trim();
    }

    private Integer nextSort() {
        return categoryRepo.findAll().stream()
                .map(DocumentCategory::getSort)
                .filter(s -> s != null)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }
}
