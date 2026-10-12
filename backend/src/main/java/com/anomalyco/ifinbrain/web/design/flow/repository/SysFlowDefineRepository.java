package com.anomalyco.ifinbrain.web.design.flow.repository;

import com.anomalyco.ifinbrain.web.design.flow.entity.SysFlowDefine;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysFlowDefineRepository extends JpaRepository<SysFlowDefine, Long> {

    /** 全量列表：供 listLaunchableForUser 过滤用（可发起清单要扫全部已发布流程） */
    List<SysFlowDefine> findAllByOrderByIdAsc();

    /** 列表页分页；排序由 Service 传进来的 Pageable 决定（默认 id 升序） */
    Page<SysFlowDefine> findAllBy(Pageable pageable);

    /** 列表页关键字搜索：名称 / 编码 / 描述任一命中即算 */
    Page<SysFlowDefine> findByNameContainingIgnoreCaseOrCodeContainingIgnoreCaseOrDescriptionContainingIgnoreCase(
            String name, String code, String description, Pageable pageable);

    Optional<SysFlowDefine> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);
}