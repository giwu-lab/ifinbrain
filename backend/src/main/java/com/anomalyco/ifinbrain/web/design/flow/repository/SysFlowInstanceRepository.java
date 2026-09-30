package com.anomalyco.ifinbrain.web.design.flow.repository;

import com.anomalyco.ifinbrain.web.design.flow.entity.SysFlowInstance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SysFlowInstanceRepository extends JpaRepository<SysFlowInstance, Long> {
}