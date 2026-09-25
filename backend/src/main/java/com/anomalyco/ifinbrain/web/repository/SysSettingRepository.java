package com.anomalyco.ifinbrain.web.repository;

import com.anomalyco.ifinbrain.web.entity.SysSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SysSettingRepository extends JpaRepository<SysSetting, String> {
}