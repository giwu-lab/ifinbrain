package com.anomalyco.ifinbrain.web.repository;

import com.anomalyco.ifinbrain.web.entity.SysPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SysPermissionRepository extends JpaRepository<SysPermission, Long> {

    Optional<SysPermission> findByCode(String code);

    boolean existsByCode(String code);

    List<SysPermission> findByIdIn(List<Long> ids);

    @Query("SELECT p FROM SysPermission p WHERE p.id IN (SELECT rp.permId FROM SysRolePerm rp WHERE rp.roleId IN :roleIds)")
    List<SysPermission> findByRoleIds(List<Long> roleIds);

    /** 类目权限码：documents:{slug}:{view|upload|update|delete} —— 按前缀匹配 */
    List<SysPermission> findByCodeStartingWith(String codePrefix);
}