package com.anomalyco.ifinbrain.web.repository;

import com.anomalyco.ifinbrain.web.entity.SysRolePerm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SysRolePermRepository extends JpaRepository<SysRolePerm, SysRolePerm.PK> {

    List<SysRolePerm> findByRoleId(Long roleId);

    boolean existsByRoleIdAndPermId(Long roleId, Long permId);

    @Modifying
    @Query("DELETE FROM SysRolePerm rp WHERE rp.roleId = :roleId")
    void deleteByRoleId(Long roleId);

    @Modifying
    @Query("DELETE FROM SysRolePerm rp WHERE rp.roleId = :roleId AND rp.permId = :permId")
    void deleteByRoleIdAndPermId(Long roleId, Long permId);

    /** 档案库权限批量解绑：删该 permId 在所有 sys_role_perm 里的行。
     *  （不要用 findByPermId + deleteAll —— permId 删自己很常见） */
    @Modifying
    @Query("DELETE FROM SysRolePerm rp WHERE rp.permId = :permId")
    void deleteByPermId(Long permId);

    @Query("SELECT rp.permId FROM SysRolePerm rp WHERE rp.roleId IN :roleIds")
    List<Long> findPermIdsByRoleIds(List<Long> roleIds);
}