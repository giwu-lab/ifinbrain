package com.anomalyco.ifinbrain.web.repository;

import com.anomalyco.ifinbrain.web.entity.SysMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SysMenuRepository extends JpaRepository<SysMenu, Long> {

    List<SysMenu> findAllByOrderByParentIdAscSortAscIdAsc();

    @Query("SELECT m FROM SysMenu m WHERE m.id IN (SELECT rm.permId FROM SysRolePerm rm WHERE rm.roleId IN :roleIds) OR m.permission IS NULL")
    List<SysMenu> findByRoleIdsOrPermissionNull(List<Long> roleIds);

    // distinct menus reachable by user's roles
    // 使用 native query（关联 sys_role_perm / sys_permission / sys_menu 三表），按 permission CODE 匹配
    //
    // DIR 可见性判定逻辑：
    //   所有 DIR 满足以下任一即显示：
    //     (a) 自身 perm 可访问（permissive fallback —— admin/operator/viewer 有全局 documents:view，
    //         即使没有任何档案库，也应能看到 Documents DIR 进去「档案库」新建第一个档案库）
    //     (b) 有至少一个可访问子菜单
    //
    //   Documents DIR (id=50) 子菜单可见性特殊：
    //     - 只认 component='documents/list/Index'（用户档案库），不认 Archive Library 系统页。
    //     - 这样用户即使有 documents:view 全局但没有任何档案库授权，DIR 也只显示 Archive Library
    //       而不显示不存在的"幽灵"档案库。
    @Query(value = """
        SELECT DISTINCT m.* FROM sys_menu m
        WHERE m.status = 1
          AND (
            (
              m.type != 'DIR'
              AND (
                m.permission IS NULL
                OR m.permission IN (
                  SELECT p1.code FROM sys_permission p1
                  INNER JOIN sys_role_perm rp1 ON rp1.perm_id = p1.id
                  WHERE rp1.role_id IN (:roleIds)
                )
              )
            )
            OR (
              m.type = 'DIR'
              AND (
                -- Documents DIR 特殊：依靠自身 perm (documents:view) 或可访问档案库子菜单
                (
                  m.id = 50
                  AND (
                    EXISTS (
                      SELECT 1 FROM sys_role_perm rp_self
                      JOIN sys_permission p_self ON p_self.id = rp_self.perm_id
                      WHERE rp_self.role_id IN (:roleIds)
                        AND p_self.code = 'documents:view'
                    )
                    OR EXISTS (
                      SELECT 1 FROM sys_menu c
                      WHERE c.parent_id = m.id AND c.status = 1
                        AND c.component = 'documents/list/Index'
                        AND c.permission IN (
                          SELECT p2.code FROM sys_permission p2
                          INNER JOIN sys_role_perm rp2 ON rp2.perm_id = p2.id
                          WHERE rp2.role_id IN (:roleIds)
                        )
                    )
                  )
                )
                OR
                -- 其他 DIR：自身 perm 或可访问子菜单
                (
                  m.id != 50
                  AND (
                    m.permission IS NULL
                    OR m.permission IN (
                      SELECT p3.code FROM sys_permission p3
                      INNER JOIN sys_role_perm rp3 ON rp3.perm_id = p3.id
                      WHERE rp3.role_id IN (:roleIds)
                    )
                    OR EXISTS (
                      SELECT 1 FROM sys_menu c
                      WHERE c.parent_id = m.id AND c.status = 1 AND (
                        c.permission IS NULL
                        OR c.permission IN (
                          SELECT p4.code FROM sys_permission p4
                          INNER JOIN sys_role_perm rp4 ON rp4.perm_id = p4.id
                          WHERE rp4.role_id IN (:roleIds)
                        )
                      )
                    )
                  )
                )
              )
            )
          )
        ORDER BY m.parent_id ASC, m.sort ASC, m.id ASC
        """, nativeQuery = true)
    List<SysMenu> findAccessibleMenus(List<Long> roleIds);

    /** 所有由 query 一键发布的菜单（query_id 非 NULL） */
    @Query("SELECT m FROM SysMenu m WHERE m.queryId IS NOT NULL")
    List<SysMenu> findAllPublishedPages();

    /**
     * 按 path 查菜单 —— 报表发布入口靠 path 反查（path = /bi/report/view/{reportId}）。
     *
     * <p>刻意不新增 report_id 外键列：path 本身就是「报表 id」的确定性映射，
     * 反过来还能当稳定可收藏的直链。加一列就得多一套 join 和一处双写。
     */
    Optional<SysMenu> findByPath(String path);

    /** 启用中的目录（报表发布的父目录候选） */
    List<SysMenu> findByTypeAndStatusOrderBySortAscIdAsc(String type, Short status);
}