package com.anomalyco.ifinbrain.web.datamgmt.repository;

import com.anomalyco.ifinbrain.web.datamgmt.entity.SysDatasource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysDatasourceRepository extends JpaRepository<SysDatasource, Long> {

    Optional<SysDatasource> findByName(String name);

    boolean existsByName(String name);

    List<SysDatasource> findAllByOrderByIdAsc();
}