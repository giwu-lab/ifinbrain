package com.anomalyco.ifinbrain.web.datamgmt.repository;

import com.anomalyco.ifinbrain.web.datamgmt.entity.SysQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysQueryRepository extends JpaRepository<SysQuery, Long> {

    List<SysQuery> findAllByOrderByIdAsc();

    /** 列表页分页；排序由 Service 传进来的 Pageable 决定（默认 id 升序） */
    Page<SysQuery> findAllBy(Pageable pageable);

    /** 列表页关键字搜索：名称 / 描述任一命中即算 */
    Page<SysQuery> findByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCase(
            String name, String description, Pageable pageable);

    boolean existsByDatasourceIdAndName(Long datasourceId, String name);
}