package com.anomalyco.fapiao.web.repository;

import com.anomalyco.fapiao.web.entity.SysRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysRoleRepository extends JpaRepository<SysRole, Long> {

    Optional<SysRole> findByCode(String code);

    List<SysRole> findByIdIn(List<Long> ids);
}