package com.anomalyco.ifinbrain.web.design.portal.repository;

import com.anomalyco.ifinbrain.web.design.portal.entity.SysPortalBlock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysPortalBlockRepository extends JpaRepository<SysPortalBlock, Long> {

    List<SysPortalBlock> findAllByOrderByCategoryAscSortAscIdAsc();

    Optional<SysPortalBlock> findByType(String type);
}