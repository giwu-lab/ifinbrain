package com.anomalyco.ifinbrain.web.preference.repository;

import com.anomalyco.ifinbrain.web.preference.entity.SysUserPreference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SysUserPreferenceRepository extends JpaRepository<SysUserPreference, Long> {
}