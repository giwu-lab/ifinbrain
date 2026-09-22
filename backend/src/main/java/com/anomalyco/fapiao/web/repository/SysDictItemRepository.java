package com.anomalyco.fapiao.web.repository;

import com.anomalyco.fapiao.web.entity.SysDictItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysDictItemRepository extends JpaRepository<SysDictItem, Long> {
    List<SysDictItem> findByDictIdOrderBySortAscIdAsc(Long dictId);
    boolean existsByDictIdAndCode(Long dictId, String code);
}