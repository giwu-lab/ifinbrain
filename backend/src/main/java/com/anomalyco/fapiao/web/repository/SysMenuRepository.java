package com.anomalyco.fapiao.web.repository;

import com.anomalyco.fapiao.web.entity.SysMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SysMenuRepository extends JpaRepository<SysMenu, Long> {

    List<SysMenu> findAllByOrderByParentIdAscSortAscIdAsc();

    @Query("SELECT m FROM SysMenu m WHERE m.id IN (SELECT rm.permId FROM SysRolePerm rm WHERE rm.roleId IN :roleIds) OR m.permission IS NULL")
    List<SysMenu> findByRoleIdsOrPermissionNull(List<Long> roleIds);

    // distinct menus reachable by user's roles
    // 使用 native query（关联 sys_role_perm / sys_permission / sys_menu 三表），按 permission CODE 匹配
    @Query(value = """
        SELECT DISTINCT m.* FROM sys_menu m
        WHERE (
          m.permission IS NULL
          OR m.permission IN (
            SELECT p.code FROM sys_permission p
            INNER JOIN sys_role_perm rp ON rp.perm_id = p.id
            WHERE rp.role_id IN (:roleIds)
          )
          OR EXISTS (
            SELECT 1 FROM sys_menu c
            WHERE c.parent_id = m.id
            AND (
              c.permission IS NULL
              OR c.permission IN (
                SELECT p2.code FROM sys_permission p2
                INNER JOIN sys_role_perm rp2 ON rp2.perm_id = p2.id
                WHERE rp2.role_id IN (:roleIds)
              )
            )
          )
        )
        AND m.status = 1
        ORDER BY m.parent_id ASC, m.sort ASC, m.id ASC
        """, nativeQuery = true)
    List<SysMenu> findAccessibleMenus(List<Long> roleIds);
}