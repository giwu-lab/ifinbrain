package com.anomalyco.ifinbrain.web.common;

import com.anomalyco.ifinbrain.web.entity.SysMenu;
import com.anomalyco.ifinbrain.web.repository.SysMenuRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「发布 = 在菜单树里生成一个可访问的入口」的共用实现。
 *
 * <p>报表和大屏共用同一套逻辑：差别只在 path / component / permission 三个字符串，
 * 行为上必须完全一致 —— 否则用户会遇到「报表能选目录、大屏不能」这种割裂。
 *
 * <p><b>查看权限怎么控</b>：菜单行的 {@code permission} 填查看类权限码
 * （报表 {@code report:view} / 大屏 {@code bi:dashboard:view}），
 * {@code SysMenuRepository#findAccessibleMenus} 只把「角色拥有该权限」的菜单发给前端。
 * 所以在「系统设置 → 角色管理」里给角色勾上这个权限，入口就出现在那个角色的
 * 侧边栏里 —— 不需要额外的授权表，这也是本方案比自建「报表-角色」关联简单的理由。
 *
 * <p><b>为什么靠 path 反查、不加外键列</b>：path 形如 {@code /bi/report/view/{id}}，
 * 本身就是「业务对象 id」的确定性映射。反过来它还是稳定可收藏的直链。
 * 加一列 report_id 就得多一处 join 和一处双写，收益只有「查得快一点点」。
 *
 * <p><b>取消发布为什么是软删</b>（status=0 而不是删行）：重新发布时能找回用户
 * 调过的排序和父目录；硬删的话这些配置就永久没了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MenuPublisher {

    /** 没指定父目录时的兜底：BI 分析目录 */
    private static final String DEFAULT_PARENT_PATH = "/bi";
    private static final String MENU_TYPE = "MENU";
    private static final String DIR_TYPE = "DIR";
    private static final String DEFAULT_ICON = "Tickets";
    private static final int DEFAULT_SORT = 99;

    private final SysMenuRepository menuRepo;

    /**
     * 候选父目录列表（发布对话框的下拉）：所有启用中的 DIR。
     *
     * <p>只按 status=1 过滤，不按 path 前缀 —— 用户可能把大屏挂在「数据管理」下，
     * 限定 /bi 前缀等于替用户做决定。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> dirs() {
        return menuRepo.findByTypeAndStatusOrderBySortAscIdAsc(DIR_TYPE, (short) 1).stream()
                .map(m -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("id", m.getId());
                    o.put("name", m.getName());
                    o.put("path", m.getPath());
                    return o;
                })
                .toList();
    }

    /** 按 path 找菜单；没有则返回 null（不是所有业务对象都发布过） */
    @Transactional(readOnly = true)
    public SysMenu find(String path) {
        return menuRepo.findByPath(path).orElse(null);
    }

    /**
     * 发布：确保 path 对应的菜单存在且启用，返回落库后的菜单。
     *
     * <p>已存在（重新发布）时**只覆盖请求里真正带了 fields** —— 用户当初把菜单
     * 挪到某个目录、改过显示名，重新发布不该把这些无声地弹回默认值。
     *
     * @param fallbackName 首次发布时菜单名的兜底（业务对象名）
     */
    @Transactional
    public SysMenu publish(String path, String permission,
                           PublishMenuReq body, String fallbackName) {
        PublishMenuReq req = (body == null) ? new PublishMenuReq() : body;

        SysMenu menu = find(path);
        if (menu == null) {
            menu = SysMenu.builder()
                    .path(path)
                    .permission(permission)
                    .type(MENU_TYPE)
                    .build();
        }

        /*
         * component 恒为 null —— 这是**刻意**的，不是漏填。
         *
         * 前端 resolveMenuName() 把 `menu.{component}` 当成侧边栏显示名的第一条候选。
         * 发布菜单如果沿用模块的 component（bi/report/View），就会命中
         * `menu.bi.report.View` =「报表展示」，于是不管发布哪张报表，侧边栏都显示同一句话，
         * 真正的报表名（sys_menu.name）永远轮不到。
         *
         * 置空后和「档案库」菜单（/documents/{slug}，component 也是共享的但没有对应 i18n key）
         * 走同一条路：i18n 候选全部落空 → 回落到 sys_menu.name，也就是报表名。
         * 代价是菜单管理页里这一行的 component 为空 —— 可以接受，它本来就不是一个模块入口。
         * 路由侧不受影响：buildRoutesFromMenus 按 path 跳过这类行（见 router/index.ts）。
         */
        menu.setComponent(null);

        if (req.getParentId() != null) {
            menu.setParentId(requireDir(req.getParentId()));
        } else if (menu.getParentId() == null) {
            menu.setParentId(defaultParentId());
        }
        if (hasText(req.getMenuName())) menu.setName(req.getMenuName().trim());
        else if (!hasText(menu.getName())) menu.setName(fallbackName);
        if (hasText(req.getIcon())) menu.setIcon(req.getIcon().trim());
        else if (!hasText(menu.getIcon())) menu.setIcon(DEFAULT_ICON);
        if (req.getSort() != null) menu.setSort(req.getSort());
        else if (menu.getSort() == null) menu.setSort(DEFAULT_SORT);

        menu.setStatus((short) 1);
        menu = menuRepo.save(menu);
        log.info("[menu] published path={} menu={} parent={}", path, menu.getId(), menu.getParentId());
        return menu;
    }

    /**
     * 取消发布：菜单软下线（status=0），从侧边栏隐藏但保留配置。
     *
     * <p>幂等：没发布过就没有菜单，直接返回 false 而不是报错 ——
     * 「取消发布」按钮对草稿也可见，报错反而莫名其妙。
     */
    @Transactional
    public boolean unpublish(String path) {
        SysMenu menu = find(path);
        if (menu == null) return false;
        menu.setStatus((short) 0);
        menuRepo.save(menu);
        log.info("[menu] unpublished path={} menu={}", path, menu.getId());
        return true;
    }

    private Long requireDir(Long parentId) {
        SysMenu dir = menuRepo.findById(parentId)
                .orElseThrow(() -> new BizException(400, "目录不存在: " + parentId));
        if (!DIR_TYPE.equals(dir.getType())) {
            throw new BizException(400, "只能挂到目录下");
        }
        return dir.getId();
    }

    private Long defaultParentId() {
        return menuRepo.findByPath(DEFAULT_PARENT_PATH).map(SysMenu::getId).orElse(0L);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
