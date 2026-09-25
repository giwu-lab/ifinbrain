package com.anomalyco.ifinbrain.web.service;

import com.anomalyco.ifinbrain.web.entity.SysMenu;
import com.anomalyco.ifinbrain.web.repository.SysMenuRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class MenuService {

    private final SysMenuRepository menuRepo;
    private final PermissionChecker perm;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> fullTree() {
        perm.require("system:menu:list");
        List<SysMenu> all = menuRepo.findAllByOrderByParentIdAscSortAscIdAsc();
        return buildTree(all);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> flatList() {
        perm.require("system:menu:list");
        return menuRepo.findAll().stream().map(this::toDto).toList();
    }

    @Transactional
    public Long create(SysMenu menu) {
        perm.require("system:menu:create");
        menuRepo.save(menu);
        return menu.getId();
    }

    @Transactional
    public void update(Long id, SysMenu menu) {
        perm.require("system:menu:update");
        SysMenu existing = menuRepo.findById(id).orElseThrow();
        existing.setParentId(menu.getParentId() != null ? menu.getParentId() : existing.getParentId());
        existing.setName(menu.getName() != null ? menu.getName() : existing.getName());
        existing.setPath(menu.getPath());
        existing.setComponent(menu.getComponent());
        if (menu.getIcon() != null) existing.setIcon(menu.getIcon());
        existing.setSort(menu.getSort() != null ? menu.getSort() : existing.getSort());
        if (menu.getPermission() != null) existing.setPermission(menu.getPermission());
        existing.setType(menu.getType() != null ? menu.getType() : existing.getType());
        existing.setStatus(menu.getStatus() != null ? menu.getStatus() : existing.getStatus());
        menuRepo.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        perm.require("system:menu:delete");
        menuRepo.findById(id).ifPresent(m -> {
            if (m.getParentId() == 0L || m.getParentId() == null) {
                // delete children as well
                menuRepo.findAll().stream()
                        .filter(c -> id.equals(c.getParentId()))
                        .forEach(c -> menuRepo.deleteById(c.getId()));
            }
            menuRepo.deleteById(id);
        });
    }

    private List<Map<String, Object>> buildTree(List<SysMenu> all) {
        Map<Long, Map<String, Object>> map = new LinkedHashMap<>();
        for (SysMenu m : all) map.put(m.getId(), toDto(m));
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> n : map.values()) {
            Long pid = (Long) n.get("parentId");
            if (pid == null || pid == 0L || !map.containsKey(pid)) {
                roots.add(n);
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> kids = (List<Map<String, Object>>) map.get(pid).get("children");
                kids.add(n);
            }
        }
        roots.sort(Comparator.comparing(m -> ((Number) m.getOrDefault("sort", 0)).intValue()));
        return roots;
    }

    private Map<String, Object> toDto(SysMenu m) {
        Map<String, Object> map = new LinkedHashMap<>();
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
        map.put("children", new ArrayList<>());
        return map;
    }
}