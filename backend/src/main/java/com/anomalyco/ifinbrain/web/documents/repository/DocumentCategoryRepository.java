package com.anomalyco.ifinbrain.web.documents.repository;

import com.anomalyco.ifinbrain.web.documents.entity.DocumentCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentCategoryRepository extends JpaRepository<DocumentCategory, Long> {

    Optional<DocumentCategory> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /** 仅返回启用类目，按 sort ASC, id ASC。供菜单构建 / 类目下拉使用。 */
    List<DocumentCategory> findAllByStatusOrderBySortAscIdAsc(Short status);
}
