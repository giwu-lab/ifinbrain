package com.anomalyco.ifinbrain.web.todo.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.anomalyco.ifinbrain.web.todo.dto.TodoTagDto;
import com.anomalyco.ifinbrain.web.todo.entity.TodoTag;
import com.anomalyco.ifinbrain.web.todo.repository.TodoTagRepository;
import com.anomalyco.ifinbrain.web.todo.repository.TodoTaskTagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service @RequiredArgsConstructor @Slf4j
public class TodoTagService {

    private final TodoTagRepository tagRepo;
    private final TodoTaskTagRepository taskTagRepo;
    private final PermissionChecker perm;

    @Transactional(readOnly = true)
    public List<TodoTagDto> list() {
        Long ownerId = perm.currentUserId();
        return tagRepo.findByOwnerIdOrderByNameAsc(ownerId).stream()
                .map(TodoTagDto::fromEntity).toList();
    }

    /** 按名 upsert（用于任务弹窗自动建标签的辅助入口；多数场景直接走任务 service）。 */
    @Transactional
    public Long upsertByName(String name) {
        perm.require("todo:task:create");
        if (name == null || name.isBlank()) {
            throw new BizException(400, "Tag name is required");
        }
        String trimmed = name.trim();
        Long ownerId = perm.currentUserId();
        return tagRepo.findByOwnerIdAndName(ownerId, trimmed)
                .map(TodoTag::getId)
                .orElseGet(() -> {
                    TodoTag t = tagRepo.save(TodoTag.builder().ownerId(ownerId).name(trimmed).build());
                    log.info("[todo] tag created id={} owner={} name={}", t.getId(), ownerId, trimmed);
                    return t.getId();
                });
    }

    @Transactional
    public void delete(Long id) {
        perm.require("todo:task:delete");
        Long ownerId = perm.currentUserId();
        TodoTag t = tagRepo.findById(id).orElseThrow(() -> new BizException(404, "Tag not found"));
        if (!t.getOwnerId().equals(ownerId)) {
            throw new BizException(404, "Tag not found");
        }
        if (taskTagRepo.countByTagId(id) > 0) {
            throw new BizException(400, "标签仍被任务引用，无法删除");
        }
        tagRepo.delete(t);
    }
}
