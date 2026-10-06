package com.anomalyco.ifinbrain.web.bi.repository;

import com.anomalyco.ifinbrain.web.bi.entity.BiDashboardEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BiDashboardRepository extends JpaRepository<BiDashboardEntity, Long> {

    /** 列表页默认排序：sort 升序（轮播序），同 sort 时最近改的排前面 */
    List<BiDashboardEntity> findAllByOrderBySortAscUpdatedAtDesc();

    Optional<BiDashboardEntity> findByCode(String code);

    boolean existsByCode(String code);

    /** 只看某个状态（0=草稿 / 1=已发布），按 sort 升序 */
    List<BiDashboardEntity> findAllByStatusOrderBySortAsc(Short status);
}
