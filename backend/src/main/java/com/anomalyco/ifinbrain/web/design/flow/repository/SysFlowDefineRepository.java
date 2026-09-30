package com.anomalyco.ifinbrain.web.design.flow.repository;

import com.anomalyco.ifinbrain.web.design.flow.entity.SysFlowDefine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysFlowDefineRepository extends JpaRepository<SysFlowDefine, Long> {

    List<SysFlowDefine> findAllByOrderByIdAsc();

    Optional<SysFlowDefine> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);
}