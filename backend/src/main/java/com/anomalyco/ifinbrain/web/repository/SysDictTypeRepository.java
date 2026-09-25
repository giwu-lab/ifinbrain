package com.anomalyco.ifinbrain.web.repository;

import com.anomalyco.ifinbrain.web.entity.SysDictType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysDictTypeRepository extends JpaRepository<SysDictType, Long> {
    Optional<SysDictType> findByCode(String code);
    List<SysDictType> findAllByOrderByIdAsc();
    boolean existsByCode(String code);
}