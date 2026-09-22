package com.anomalyco.fapiao.web.repository;

import com.anomalyco.fapiao.web.entity.SysSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SysSettingRepository extends JpaRepository<SysSetting, String> {
}