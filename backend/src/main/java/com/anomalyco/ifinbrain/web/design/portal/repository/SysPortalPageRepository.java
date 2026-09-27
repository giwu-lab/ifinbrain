package com.anomalyco.ifinbrain.web.design.portal.repository;

import com.anomalyco.ifinbrain.web.design.portal.entity.SysPortalPage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysPortalPageRepository extends JpaRepository<SysPortalPage, Long> {

    List<SysPortalPage> findAllByOrderByIdAsc();

    Optional<SysPortalPage> findByCode(String code);

    boolean existsByCode(String code);
}