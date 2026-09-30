package com.anomalyco.ifinbrain.web.design.form.repository;

import com.anomalyco.ifinbrain.web.design.form.entity.SysFormDesign;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Set;

public interface SysFormDesignRepository extends JpaRepository<SysFormDesign, Long> {

    List<SysFormDesign> findAllByOrderByIdAsc();

    java.util.Optional<SysFormDesign> findByCode(String code);

    boolean existsByCode(String code);

    /** 用于更新时排除自身后判定 code 是否冲突 */
    boolean existsByCodeAndIdNot(String code, Long id);

    Set<SysFormDesign> findByCodeIn(Set<String> codes);
}