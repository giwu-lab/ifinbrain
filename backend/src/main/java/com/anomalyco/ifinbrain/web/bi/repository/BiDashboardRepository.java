package com.anomalyco.ifinbrain.web.bi.repository;

import com.anomalyco.ifinbrain.web.bi.entity.BiDashboardEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BiDashboardRepository extends JpaRepository<BiDashboardEntity, Long> {

    /** 列表页分页；排序由 Service 传进来的 Pageable 决定（默认 sort 升序 + updatedAt 降序） */
    Page<BiDashboardEntity> findAllBy(Pageable pageable);

    /** 列表页关键字搜索：名称 / 编码 / 描述任一命中即算 */
    Page<BiDashboardEntity> findByNameContainingIgnoreCaseOrCodeContainingIgnoreCaseOrDescriptionContainingIgnoreCase(
            String name, String code, String description, Pageable pageable);

    Optional<BiDashboardEntity> findByCode(String code);

    boolean existsByCode(String code);

    /** 只看某个状态（0=草稿 / 1=已发布），按 sort 升序 */
    List<BiDashboardEntity> findAllByStatusOrderBySortAsc(Short status);
}