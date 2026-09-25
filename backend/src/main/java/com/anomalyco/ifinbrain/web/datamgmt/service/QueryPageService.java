package com.anomalyco.ifinbrain.web.datamgmt.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.datamgmt.dto.PublishQueryPageReq;
import com.anomalyco.ifinbrain.web.datamgmt.dto.UpdateQueryPageReq;
import com.anomalyco.ifinbrain.web.datamgmt.entity.SysQuery;
import com.anomalyco.ifinbrain.web.datamgmt.repository.SysQueryRepository;
import com.anomalyco.ifinbrain.web.entity.SysMenu;
import com.anomalyco.ifinbrain.web.repository.SysMenuRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.anomalyco.ifinbrain.web.service.MenuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一键把 saved query 发布为可访问的列表页（生成 sys_menu 条目）。
 * 所有发布的页面共享同一组件 data-mgmt/query-page/Index。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryPageService {

    /** /data-mgmt 目录的 parent_id（与 V10 migration 保持一致） */
    private static final Long DATA_MGMT_DIR_ID = 40L;

    private final SysQueryRepository queryRepo;
    private final SysMenuRepository menuRepo;
    private final PermissionChecker perm;

    /** 列出所有已发布的查询页 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        perm.require("data:query:view");
        return menuRepo.findAllByOrderByParentIdAscSortAscIdAsc().stream()
                .filter(m -> m.getQueryId() != null && "MENU".equals(m.getType()))
                .map(QueryPageService::toDto)
                .toList();
    }

    /** 发布：从 saved query 创建 menu 条目 */
    @Transactional
    public Map<String, Object> publish(PublishQueryPageReq req) {
        perm.require("data:query:publish");
        SysQuery q = queryRepo.findById(req.getQueryId())
                .orElseThrow(() -> new BizException(400, "查询不存在: " + req.getQueryId()));
        if (q.getStatus() != null && q.getStatus() == 0) {
            throw new BizException(400, "查询已禁用，无法发布");
        }

        String menuName = (req.getMenuName() == null || req.getMenuName().isBlank())
                ? q.getName() : req.getMenuName().trim();

        String icon = (req.getIcon() == null || req.getIcon().isBlank())
                ? "Document" : req.getIcon().trim();

        int sort = req.getSort() == null ? 99 : req.getSort();

        String configJson = (req.getConfigJson() == null || req.getConfigJson().isBlank())
                ? "{}" : req.getConfigJson();

        // 先存一次拿 id，再设 path（path 里包含 id）
        SysMenu m = SysMenu.builder()
                .parentId(DATA_MGMT_DIR_ID)
                .name(menuName)
                .component("data-mgmt/query-page/Index")
                .permission("data:query:view")
                .icon(icon)
                .sort(sort)
                .type("MENU")
                .status((short) 1)
                .queryId(q.getId())
                .configJson(configJson)
                .path("/data-mgmt/query-page/PLACEHOLDER")
                .build();
        m = menuRepo.save(m);
        m.setPath("/data-mgmt/query-page/" + m.getId());
        m = menuRepo.save(m);

        log.info("[query-page] published menu id={} query={} ({})", m.getId(), q.getId(), q.getName());
        return toDto(m);
    }

    /** 取消发布：软删（status=0，从侧边栏隐藏，可恢复） */
    @Transactional
    public Map<String, Object> unpublish(Long menuId) {
        perm.require("data:query:publish");
        SysMenu m = menuRepo.findById(menuId)
                .orElseThrow(() -> new BizException(404, "页面不存在"));
        if (m.getQueryId() == null) {
            throw new BizException(400, "该菜单不是查询页面");
        }
        m.setStatus((short) 0);
        m = menuRepo.save(m);
        log.info("[query-page] unpublished (soft) menu id={}", menuId);
        return toDto(m);
    }

    /** 重新发布：恢复 status=1 */
    @Transactional
    public Map<String, Object> republish(Long menuId) {
        perm.require("data:query:publish");
        SysMenu m = menuRepo.findById(menuId)
                .orElseThrow(() -> new BizException(404, "页面不存在"));
        if (m.getQueryId() == null) {
            throw new BizException(400, "该菜单不是查询页面");
        }
        m.setStatus((short) 1);
        m = menuRepo.save(m);
        log.info("[query-page] republished menu id={}", menuId);
        return toDto(m);
    }

    /** 硬删除：永久移除 menu 行 */
    @Transactional
    public void delete(Long menuId) {
        perm.require("data:query:publish");
        SysMenu m = menuRepo.findById(menuId)
                .orElseThrow(() -> new BizException(404, "页面不存在"));
        if (m.getQueryId() == null) {
            throw new BizException(400, "该菜单不是查询页面");
        }
        menuRepo.delete(m);
        log.info("[query-page] hard-deleted menu id={}", menuId);
    }

    /** 编辑发布：修改 menuName + configJson（不改 queryId / 关联的 query） */
    @Transactional
    public Map<String, Object> update(Long menuId, UpdateQueryPageReq req) {
        perm.require("data:query:publish");
        SysMenu m = menuRepo.findById(menuId)
                .orElseThrow(() -> new BizException(404, "页面不存在"));
        if (m.getQueryId() == null) {
            throw new BizException(400, "该菜单不是查询页面");
        }
        if (req.getMenuName() != null && !req.getMenuName().isBlank()) {
            m.setName(req.getMenuName().trim());
        }
        if (req.getIcon() != null) {
            // null = 不改；空字符串 = 设为默认 Document
            m.setIcon(req.getIcon().isBlank() ? "Document" : req.getIcon().trim());
        }
        if (req.getSort() != null) {
            m.setSort(req.getSort());
        }
        if (req.getConfigJson() != null) {
            m.setConfigJson(req.getConfigJson().isBlank() ? "{}" : req.getConfigJson());
        }
        m = menuRepo.save(m);
        log.info("[query-page] updated menu id={} configBytes={}",
                menuId, m.getConfigJson() == null ? 0 : m.getConfigJson().length());
        return toDto(m);
    }

    /** 给前端解析：menuId → queryId */
    @Transactional(readOnly = true)
    public Long resolveQueryId(Long menuId) {
        perm.require("data:query:view");
        return menuRepo.findById(menuId)
                .map(SysMenu::getQueryId)
                .orElseThrow(() -> new BizException(404, "页面不存在"));
    }

    /** 完整菜单详情（含 configJson）—— 给生成的页面用 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long menuId) {
        perm.require("data:query:view");
        SysMenu m = menuRepo.findById(menuId)
                .orElseThrow(() -> new BizException(404, "页面不存在"));
        return toDto(m);
    }

private static Map<String, Object> toDto(SysMenu m) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", m.getId());
        map.put("parentId", m.getParentId());
        map.put("name", m.getName());
        map.put("path", m.getPath());
        map.put("component", m.getComponent());
        map.put("icon", m.getIcon());
        map.put("sort", m.getSort());
        map.put("permission", m.getPermission());
        map.put("type", m.getType());
        map.put("status", m.getStatus());
        map.put("queryId", m.getQueryId());
        map.put("configJson", m.getConfigJson());
        return map;
    }
}