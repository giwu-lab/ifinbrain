package com.anomalyco.ifinbrain.web.documents.repository;

import com.anomalyco.ifinbrain.web.documents.entity.ArchiveLibrary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ArchiveLibraryRepository extends JpaRepository<ArchiveLibrary, Long> {

    Optional<ArchiveLibrary> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /** 仅返回启用档案库，按 sort ASC, id ASC。供菜单构建 / 下拉使用。 */
    List<ArchiveLibrary> findAllByStatusOrderBySortAscIdAsc(Short status);
}
