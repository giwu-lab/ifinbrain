package com.anomalyco.ifinbrain.web.datamgmt.repository;

import com.anomalyco.ifinbrain.web.datamgmt.entity.SysDatasource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysDatasourceRepository extends JpaRepository<SysDatasource, Long> {

    Optional<SysDatasource> findByName(String name);

    boolean existsByName(String name);

    /**
     * 全量列表（按 id 升序）。
     *
     * <p>仅供「下拉选数据源」这类必须看到全部项的场景（查询详情页、导入向导）；
     * 列表页走 {@link #findAllBy(Pageable)} 真分页。
     */
    List<SysDatasource> findAllByOrderByIdAsc();

    /** 列表页分页；排序由 Service 传进来的 Pageable 决定（默认 id 升序） */
    Page<SysDatasource> findAllBy(Pageable pageable);

    /** 列表页关键字搜索：名称 / 主机 / 库名 / 账号 / 备注任一命中即算 */
    Page<SysDatasource> findByNameContainingIgnoreCaseOrHostContainingIgnoreCaseOrDatabaseNameContainingIgnoreCaseOrUsernameContainingIgnoreCaseOrRemarkContainingIgnoreCase(
            String name, String host, String databaseName, String username, String remark, Pageable pageable);
}