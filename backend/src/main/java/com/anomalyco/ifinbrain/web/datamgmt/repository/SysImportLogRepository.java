package com.anomalyco.ifinbrain.web.datamgmt.repository;

import com.anomalyco.ifinbrain.web.datamgmt.entity.SysImportLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysImportLogRepository extends JpaRepository<SysImportLog, Long> {

    Page<SysImportLog> findAllByOrderByIdDesc(Pageable pageable);

    Page<SysImportLog> findByDatasourceIdOrderByIdDesc(Long datasourceId, Pageable pageable);

    List<SysImportLog> findTop50ByOrderByIdDesc();
}