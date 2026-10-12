package com.anomalyco.ifinbrain.web.documents.repository;

import com.anomalyco.ifinbrain.web.documents.entity.ArchiveLibrary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ArchiveLibraryRepository extends JpaRepository<ArchiveLibrary, Long> {

    Optional<ArchiveLibrary> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /** 仅返回启用档案库，按 sort ASC, id ASC。供菜单构建 / 下拉使用。 */
    List<ArchiveLibrary> findAllByStatusOrderBySortAscIdAsc(Short status);

    /**
     * 只取 slug 一列，不取整行。
     *
     * <p>这个方法是让「档案库列表能分页」的关键：档案库的可见性按 {@code documents:{slug}:view}
     * 逐行动态判定 —— 若先把整表拉出来再逐行 has()，过滤完就无从分页（页大小与 total 都会失真）。
     * 这里只取一列 slug 反推「有权 slug 集合」，真正的切片交给下面的 {@code SlugIn} 在 DB 里做。
     */
    @Query("select a.slug from ArchiveLibrary a where a.status = 1")
    List<String> findEnabledSlugs();

    /** 分页查询：限定在给定 slug 集合内，按 sort ASC, id ASC */
    Page<ArchiveLibrary> findAllByStatusAndSlugInOrderBySortAscIdAsc(
            Short status, Collection<String> slugs, Pageable pageable);
}