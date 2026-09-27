package com.anomalyco.ifinbrain.web.design.portal.service;

import com.anomalyco.ifinbrain.web.design.portal.dto.PortalBlockDto;
import com.anomalyco.ifinbrain.web.design.portal.entity.SysPortalBlock;
import com.anomalyco.ifinbrain.web.design.portal.repository.SysPortalBlockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PortalBlockService {

    private final SysPortalBlockRepository repo;

    @Transactional(readOnly = true)
    public List<PortalBlockDto> listEnabled() {
        // 该接口给前端编辑器调用，用于画左侧组件库；需要登录但不做细粒度权限控制
        // （任何登录用户都能预览组件形态；写操作走 design:page:update 守护）
        return repo.findAllByOrderByCategoryAscSortAscIdAsc().stream()
                .filter(b -> b.getEnabled() != null && b.getEnabled() == 1)
                .map(PortalBlockDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PortalBlockDto> listAll() {
        return repo.findAllByOrderByCategoryAscSortAscIdAsc().stream()
                .map(PortalBlockDto::from)
                .toList();
    }
}