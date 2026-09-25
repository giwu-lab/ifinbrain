package com.anomalyco.ifinbrain.web.datamgmt.repository;

import com.anomalyco.ifinbrain.web.datamgmt.entity.SysQuery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysQueryRepository extends JpaRepository<SysQuery, Long> {

    List<SysQuery> findAllByOrderByIdAsc();

    boolean existsByDatasourceIdAndName(Long datasourceId, String name);
}