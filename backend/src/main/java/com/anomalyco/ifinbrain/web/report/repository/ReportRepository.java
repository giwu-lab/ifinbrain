package com.anomalyco.ifinbrain.web.report.repository;

import com.anomalyco.ifinbrain.web.report.entity.ReportEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportRepository extends JpaRepository<ReportEntity, Long> {

    /** 列表页分页；排序由 Service 传进来的 Pageable 决定（默认 updatedAt 降序） */
    Page<ReportEntity> findAllBy(Pageable pageable);

    /** 列表页关键字搜索：名称 / 编码 / 描述任一命中即算 */
    Page<ReportEntity> findByNameContainingIgnoreCaseOrCodeContainingIgnoreCaseOrDescriptionContainingIgnoreCase(
            String name, String code, String description, Pageable pageable);

    List<ReportEntity> findAllByStatusOrderByUpdatedAtDesc(Short status);

    boolean existsByCode(String code);

    /** 反查「哪些报表用了这个查询」—— 删查询前必须先提示 */
    List<ReportEntity> findAllByQueryId(Long queryId);
}