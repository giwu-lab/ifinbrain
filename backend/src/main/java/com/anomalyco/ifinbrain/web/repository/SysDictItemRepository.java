package com.anomalyco.ifinbrain.web.repository;

import com.anomalyco.ifinbrain.web.entity.SysDictItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysDictItemRepository extends JpaRepository<SysDictItem, Long> {
    List<SysDictItem> findByDictIdOrderBySortAscIdAsc(Long dictId);
    boolean existsByDictIdAndCode(Long dictId, String code);

    /** V43 任务标签：按 dict_id + label 查找唯一条目。 */
    java.util.Optional<SysDictItem> findByDictIdAndLabel(Long dictId, String label);
}