package com.anomalyco.ifinbrain.web.repository;

import com.anomalyco.ifinbrain.web.entity.SysDept;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysDeptRepository extends JpaRepository<SysDept, Long> {

    /** 树形渲染的稳定顺序：先按 parent，再按 sort，最后按 id（保证同 sort 时顺序不跳） */
    List<SysDept> findAllByOrderByParentIdAscSortAscIdAsc();

    List<SysDept> findByParentId(Long parentId);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);
}
