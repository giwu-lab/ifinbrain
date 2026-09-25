package com.anomalyco.ifinbrain.web.repository;

import com.anomalyco.ifinbrain.web.entity.SysRolePerm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SysRolePermRepository extends JpaRepository<SysRolePerm, SysRolePerm.PK> {

    List<SysRolePerm> findByRoleId(Long roleId);

    @Modifying
    @Query("DELETE FROM SysRolePerm rp WHERE rp.roleId = :roleId")
    void deleteByRoleId(Long roleId);

    @Query("SELECT rp.permId FROM SysRolePerm rp WHERE rp.roleId IN :roleIds")
    List<Long> findPermIdsByRoleIds(List<Long> roleIds);
}