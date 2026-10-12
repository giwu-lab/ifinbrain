package com.anomalyco.ifinbrain.web.bi.repository;

import com.anomalyco.ifinbrain.web.bi.entity.BiDashboardEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BiDashboardRepository extends JpaRepository<BiDashboardEntity, Long> {

    /** 列表页分页；排序由 Service 传进来的 Pageable 决定（默认 sort 升序 + updatedAt 降序） */
    Page<BiDashboardEntity> findAllBy(Pageable pageable);

    /**
     * 实体级授权后的列表分页：{@code allowedCodes} 是当前用户持有
     * {@code bi:dashboard:{code}:view} 的 code 集合。
     *
     * <p>过滤必须在 DB 里做 —— 先分页再在内存里过滤，每页行数会忽多忽少，
     * 末页还会整页漏掉。
     */
    Page<BiDashboardEntity> findAllByCodeIn(Collection<String> allowedCodes, Pageable pageable);

    /** 列表页关键字搜索：名称 / 编码 / 描述任一命中即算，再叠加实体级可见性 */
    Page<BiDashboardEntity> findByNameContainingIgnoreCaseOrCodeContainingIgnoreCaseOrDescriptionContainingIgnoreCaseAndCodeIn(
            String name, String code, String description, Collection<String> allowedCodes, Pageable pageable);

    Optional<BiDashboardEntity> findByCode(String code);

    boolean existsByCode(String code);

    /** 只看某个状态（0=草稿 / 1=已发布），按 sort 升序，且必须在可见集合内 */
    List<BiDashboardEntity> findAllByStatusAndCodeInOrderBySortAsc(Short status, Collection<String> allowedCodes);
}