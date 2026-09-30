package com.anomalyco.ifinbrain.web.design.flow.repository;

import com.anomalyco.ifinbrain.web.design.flow.entity.SysFlowHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SysFlowHistoryRepository extends JpaRepository<SysFlowHistory, Long> {
}