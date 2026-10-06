package com.anomalyco.ifinbrain.web.todo.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.entity.SysUser;
import com.anomalyco.ifinbrain.web.repository.SysUserRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.anomalyco.ifinbrain.web.todo.dto.TodoAssigneeOption;
import com.anomalyco.ifinbrain.web.todo.dto.TodoDashboardItem;
import com.anomalyco.ifinbrain.web.todo.dto.TodoDashboardResponse;
import com.anomalyco.ifinbrain.web.todo.dto.TodoTaskCreateReq;
import com.anomalyco.ifinbrain.web.todo.dto.TodoTaskDto;
import com.anomalyco.ifinbrain.web.todo.dto.TodoTaskUpdateReq;
import com.anomalyco.ifinbrain.web.todo.entity.TodoTask;
import com.anomalyco.ifinbrain.web.todo.entity.TodoTaskTag;
import com.anomalyco.ifinbrain.web.todo.repository.TodoTaskRepository;
import com.anomalyco.ifinbrain.web.todo.repository.TodoTaskTagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.DayOfWeek;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务清单业务逻辑。设计要点：
 * - assignee 隔离：列表/详情/改删按 assigneeId 过滤；持 todo:task:view-all 的角色跳过过滤。
 *   该权限属于 admin 角色与「任务管理者」预设角色（见 SystemPresets）。
 * - 「我的任务」（仪表盘卡片 + widget）**永远**只看 assignee = me，不受 view-all 影响。
 * - 完成态 + 循环任务：complete() 在同一事务里标记当前 + spawn 下一条。
 * - 标签清理：删任务后再扫一次 sys_todo_tag，把没有 task_tag 引用的孤儿标签一并删掉。
 * - 标签所有权：标签记在「任务执行人」名下（admin 创建给 B → 标签归 B）。
 * - 重指派 perm：admin (todo:task:assign) 或任务创建者（createdBy = me）可改 assigneeId。
 */
@Service @RequiredArgsConstructor @Slf4j
public class TodoTaskService {

    private final TodoTaskRepository taskRepo;
    private final TodoTaskTagRepository taskTagRepo;
    private final TodoTagService tagService;
    private final SysUserRepository userRepo;
    private final PermissionChecker perm;

    // ---- 列表 / 详情 ----

    @Transactional(readOnly = true)
    public Map<String, Object> page(int page, int size, String status, String priority, String keyword) {
        Long me = perm.currentUserId();
        boolean adminViewAll = perm.has("todo:task:view-all");
        Page<TodoTask> result = adminViewAll
                ? taskRepo.searchAll(PageRequest.of(Math.max(0, page - 1), size))
                : taskRepo.searchByAssignee(me, PageRequest.of(Math.max(0, page - 1), size));

        List<TodoTask> filtered = result.getContent().stream()
                .filter(t -> status == null || status.isBlank() || status.equalsIgnoreCase(t.getStatus()))
                .filter(t -> priority == null || priority.isBlank() || priority.equalsIgnoreCase(t.getPriority()))
                .filter(t -> {
                    if (keyword == null || keyword.isBlank()) return true;
                    String k = keyword.toLowerCase();
                    return (t.getTitle() != null && t.getTitle().toLowerCase().contains(k))
                        || (t.getDescription() != null && t.getDescription().toLowerCase().contains(k));
                })
                // 未完成优先（v1.1.3 排序规则），其余按 dueAt 升序 / id 倒序
                .sorted(Comparator
                        .comparing((TodoTask t) -> "COMPLETED".equals(t.getStatus()) ? 1 : 0)
                        .thenComparing(t -> Optional.ofNullable(t.getDueAt()).orElse(LocalDateTime.MAX))
                        .thenComparing(Comparator.comparing(TodoTask::getId).reversed()))
                .toList();
        List<TodoTaskDto> items = attachTagsAndNames(filtered);
        Map<String, Object> resp = new HashMap<>();
        resp.put("items", items);
        resp.put("total", filtered.size());
        resp.put("page", page);
        resp.put("size", size);
        resp.put("adminViewAll", adminViewAll);
        return resp;
    }

    @Transactional(readOnly = true)
    public TodoTaskDto detail(Long id) {
        perm.require("todo:task:view");
        TodoTask t = loadAndAuthorize(id, false);
        List<Long> tagIds = taskTagRepo.findDictItemIdsByTaskId(t.getId());
        List<String> tagNames = tagService.labelMap(tagIds).values().stream().toList();
        return enrich(attachTagsAndNames(List.of(t)).get(0), tagIds, tagNames);
    }

    /** 父任务下拉数据源：当前 assignee 的 PENDING 任务（排除自己）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> parentCandidates(Long excludeId) {
        Long me = perm.currentUserId();
        List<TodoTask> pending = taskRepo.findByAssigneeIdAndStatusOrderByIdDesc(me, "PENDING");
        return pending.stream()
                .filter(t -> excludeId == null || !t.getId().equals(excludeId))
                .map(t -> Map.<String, Object>of(
                        "id", t.getId(),
                        "title", t.getTitle(),
                        "priority", t.getPriority(),
                        "dueAt", t.getDueAt() == null ? "" : t.getDueAt().toString()))
                .toList();
    }

    /** assignee 选项（仅启用用户，按 username 升序）。 */
    @Transactional(readOnly = true)
    public List<TodoAssigneeOption> assigneeOptions() {
        return userRepo.findAllByStatusOrderByUsernameAsc((short) 1).stream()
                .map(u -> TodoAssigneeOption.builder()
                        .id(u.getId())
                        .username(u.getUsername())
                        .displayName(u.getDisplayName())
                        .build())
                .toList();
    }

    // ---- 创建 / 更新 / 完成 / 删除 ----

    @Transactional
    public Long create(TodoTaskCreateReq req) {
        perm.require("todo:task:create");
        Long me = perm.currentUserId();
        String priority = normalizeEnum("priority", req.getPriority(), TodoTask.Priority.class, TodoTask.Priority.NONE);
        String recurrence = normalizeEnum("recurrence", req.getRecurrence(), TodoTask.Recurrence.class, TodoTask.Recurrence.NONE);

        // admin 或创建者（自创建时 assignee = me 也算）可指定 assigneeId；
        // 其他人 req.assigneeId 必须为 null 或 = me，否则忽略。
        Long assigneeId = resolveAssigneeOnCreate(req.getAssigneeId(), me);

        // 循环规则完整性校验：WEEKLY/MONTHLY/YEARLY 必须配具体日期，否则保存到 null fallback
        Integer[] recurrenceCfg = resolveRecurrenceConfig(recurrence, req.getRecurrenceDow(), req.getRecurrenceDom(), req.getRecurrenceMonth());

        TodoTask t = TodoTask.builder()
                .assigneeId(assigneeId)
                .createdBy(me)
                .title(req.getTitle().trim())
                .description(req.getDescription())
                .priority(priority)
                .status(TodoTask.Status.PENDING.name())
                .dueAt(req.getDueAt())
                .parentId(validateParent(assigneeId, req.getParentId(), null))
                .recurrence(recurrence)
                .recurrenceDow(recurrenceCfg[0])
                .recurrenceDom(recurrenceCfg[1])
                .recurrenceMonth(recurrenceCfg[2])
                .build();
        taskRepo.save(t);
        // 标签写入 todo_tag 字典（V43 起全局共享，不再按 assignee 隔离）
        replaceTags(t.getId(), req.getTagNames());
        log.info("[todo] task created id={} assignee={} createdBy={}", t.getId(), assigneeId, me);
        return t.getId();
    }

    @Transactional
    public void update(Long id, TodoTaskUpdateReq req) {
        perm.require("todo:task:update");
        Long me = perm.currentUserId();
        TodoTask t = loadAndAuthorize(id, true);

        if (req.getTitle() != null) t.setTitle(req.getTitle().trim());
        if (req.getDescription() != null) t.setDescription(req.getDescription());
        if (req.getPriority() != null) {
            t.setPriority(normalizeEnum("priority", req.getPriority(), TodoTask.Priority.class, null));
        }
        if (req.getStatus() != null) {
            t.setStatus(normalizeEnum("status", req.getStatus(), TodoTask.Status.class, null));
            if (TodoTask.Status.COMPLETED.name().equals(t.getStatus()) && t.getCompletedAt() == null) {
                t.setCompletedAt(LocalDateTime.now());
            }
        }
        if (req.getRecurrence() != null) {
            String newRec = normalizeEnum("recurrence", req.getRecurrence(), TodoTask.Recurrence.class, null);
            // 编辑 recurrence 时若同时给了 dow/dom/month，一并更新；
            // 若只改了 recurrence 类型（清空配置），按新类型完整性校验做 fallback
            Integer[] recurrenceCfg;
            if (req.getRecurrenceDow() != null || req.getRecurrenceDom() != null || req.getRecurrenceMonth() != null) {
                recurrenceCfg = resolveRecurrenceConfig(newRec,
                        req.getRecurrenceDow() != null ? req.getRecurrenceDow() : t.getRecurrenceDow(),
                        req.getRecurrenceDom() != null ? req.getRecurrenceDom() : t.getRecurrenceDom(),
                        req.getRecurrenceMonth() != null ? req.getRecurrenceMonth() : t.getRecurrenceMonth());
            } else {
                // 没传配置：保留现有值，仅校验完整性
                recurrenceCfg = resolveRecurrenceConfig(newRec,
                        t.getRecurrenceDow(), t.getRecurrenceDom(), t.getRecurrenceMonth());
            }
            t.setRecurrence(newRec);
            t.setRecurrenceDow(recurrenceCfg[0]);
            t.setRecurrenceDom(recurrenceCfg[1]);
            t.setRecurrenceMonth(recurrenceCfg[2]);
        } else if (req.getRecurrenceDow() != null || req.getRecurrenceDom() != null || req.getRecurrenceMonth() != null) {
            // 仅改配置：保留 recurrence 类型，但更新配置（同样过完整性校验）
            Integer[] recurrenceCfg = resolveRecurrenceConfig(t.getRecurrence(),
                    req.getRecurrenceDow() != null ? req.getRecurrenceDow() : t.getRecurrenceDow(),
                    req.getRecurrenceDom() != null ? req.getRecurrenceDom() : t.getRecurrenceDom(),
                    req.getRecurrenceMonth() != null ? req.getRecurrenceMonth() : t.getRecurrenceMonth());
            t.setRecurrenceDow(recurrenceCfg[0]);
            t.setRecurrenceDom(recurrenceCfg[1]);
            t.setRecurrenceMonth(recurrenceCfg[2]);
        }
        if (req.getDueAt() != null) t.setDueAt(req.getDueAt());
        if (req.getParentId() != null) {
            // parent 校验时按当前任务的 assigneeId（重新指派后用新 assignee 校验）
            t.setParentId(validateParent(t.getAssigneeId(), req.getParentId(), id));
        }
        if (req.getTagNames() != null) {
            replaceTags(id, req.getTagNames());
        }
        // status 切回 PENDING 时清空 completed_at
        if (TodoTask.Status.PENDING.name().equals(t.getStatus()) && t.getCompletedAt() != null
                && (req.getStatus() != null)) {
            t.setCompletedAt(null);
        }
        // 重指派 assignee（最后处理，因为它会改变后续 parent/tag 校验上下文 —— 已无需二次校验）
        if (req.getAssigneeId() != null && !req.getAssigneeId().equals(t.getAssigneeId())) {
            if (!canReassign(t, me)) {
                throw new BizException(403, "仅任务创建者或管理员可修改执行人");
            }
            Long oldAssignee = t.getAssigneeId();
            t.setAssigneeId(req.getAssigneeId());
            log.info("[todo] reassign: task={} {} -> {} by user={}", id, oldAssignee, req.getAssigneeId(), me);
            // 重新指派后，已关联的标签行保留在 sys_todo_task_tag 中 —— 标签链不丢；
            // 但下次删除任务清理时，orphan 清理会按 **当前** assignee（即 removeTasks 的 owner）
            // 做处理。这是 Admin 实现可能需要考虑：跨 assignee 转交时是否迁移标签？
            // 决定：不迁移（避免误删多用户共享的标签），保持原标签归属。
        }
    }

    /**
     * 标记完成：状态切 COMPLETED；若 recurrence != NONE 则同事务内生成下一条 PENDING 任务。
     * 返回新生成任务的 id（若未生成则 null）。
     */
    @Transactional
    public Long complete(Long id) {
        perm.require("todo:task:update");
        Long me = perm.currentUserId();
        TodoTask t = loadAndAuthorize(id, true);

        LocalDateTime now = LocalDateTime.now();
        t.setStatus(TodoTask.Status.COMPLETED.name());
        t.setCompletedAt(now);
        taskRepo.save(t);

        if (TodoTask.Recurrence.NONE.name().equals(t.getRecurrence())) {
            return null;
        }
        LocalDateTime baseDue = t.getDueAt() != null ? t.getDueAt() : now;
        LocalDateTime nextDue = advance(baseDue, t.getRecurrence(),
                t.getRecurrenceDow(), t.getRecurrenceDom(), t.getRecurrenceMonth());

        TodoTask next = TodoTask.builder()
                .assigneeId(t.getAssigneeId())
                .createdBy(t.getCreatedBy() != null ? t.getCreatedBy() : t.getAssigneeId())
                .title(t.getTitle())
                .description(t.getDescription())
                .priority(t.getPriority())
                .status(TodoTask.Status.PENDING.name())
                .dueAt(nextDue)
                .parentId(null)
                .recurrence(t.getRecurrence())
                .recurrenceDow(t.getRecurrenceDow())
                .recurrenceDom(t.getRecurrenceDom())
                .recurrenceMonth(t.getRecurrenceMonth())
                .build();
        taskRepo.save(next);
        // 循环任务的下一条直接复用原关联行 —— dict_item_id 指向字典条目，
        // 条目是全局共享的，不存在「每个执行人一套标签」需要重新 upsert 的问题。
        List<Long> oldTagIds = taskTagRepo.findDictItemIdsByTaskId(t.getId());
        for (Long tagId : oldTagIds) {
            taskTagRepo.save(TodoTaskTag.builder().taskId(next.getId()).dictItemId(tagId).build());
        }
        log.info("[todo] recurrence: task={} -> next={} dueAt={}", t.getId(), next.getId(), nextDue);
        return next.getId();
    }

    @Transactional
    public void delete(Long id) {
        perm.require("todo:task:delete");
        Long me = perm.currentUserId();
        TodoTask t = loadAndAuthorize(id, true);
        // 先记下本任务用过的标签：删完之后只有这几个可能变成无引用。
        // 必须在 delete 之前取，因为 task_tag 行会被 ON DELETE CASCADE 一起清掉。
        List<Long> usedTagIds = taskTagRepo.findDictItemIdsByTaskId(t.getId());
        taskRepo.delete(t);
        // 关键：cascade 是数据库层 ON DELETE CASCADE，Hibernate 不知情；
        // 必须在 deleteOrphans 前 flush，让 cascade 真实落到 DB，
        // 否则后续 NOT EXISTS 子查询还会看到旧的 task_tag 行。
        taskRepo.flush();
        // 子任务 SET NULL（FK）—— 不级联删子，符合"删除父任务不级联子"的常识。
        // 标签关联由 ON DELETE CASCADE 自动清理；这里再把「本任务用过、且现已无引用」
        // 的那几条标签回收掉（v1.1.3）。只回收本任务这几个，不会波及字典里其它未使用的标签。
        tagService.deleteOrphans(usedTagIds);
    }

    // ---- 仪表盘 ----

    @Transactional(readOnly = true)
    public TodoDashboardResponse dashboard(int limit) {
        Long me = perm.currentUserId();
        int cap = limit <= 0 ? 10 : Math.min(limit, 50);

        // 「我的任务」恒等于 assignee = me —— 不再因为持有 todo:task:view-all
        // 就变成全员 PENDING 计数。仪表盘卡片和 widget 都叫「我的任务」，
        // 混入别人的任务会让数字失去意义（admin 明明只有 5 条却显示 10）。
        // 全员视图只属于任务列表页 page()：那里有「执行人」列可以对照，
        // 并且带明确的「执行人: 全员」徽标，不会被误读。
        List<TodoTask> rows = taskRepo.findDashboard(me, LocalDateTime.now(), PageRequest.of(0, cap));
        long total = taskRepo.countByAssigneeIdAndStatus(me, TodoTask.Status.PENDING.name());

        List<TodoDashboardItem> items = rows.stream().map(t -> TodoDashboardItem.builder()
                .id(t.getId())
                .title(t.getTitle())
                .priority(t.getPriority())
                .dueAt(t.getDueAt())
                .recurrence(t.getRecurrence())
                .build()).toList();

        return TodoDashboardResponse.builder().items(items).total(total).build();
    }

    // ---- 跨模块：附件服务需要校验任务可见性 ----

    /**
     * 校验当前用户可访问任务（assignee = me 或 has view-all perm）。
     * 找到任务并返回；未命中抛 404。
     */
    @Transactional(readOnly = true)
    public TodoTask loadForAttachment(Long taskId) {
        perm.require("todo:task:view");
        Long me = perm.currentUserId();
        boolean admin = perm.has("todo:task:view-all");
        return taskRepo.findById(taskId)
                .filter(t -> admin || me.equals(t.getAssigneeId()))
                .orElseThrow(() -> new BizException(404, "Task not found"));
    }

    // ---- 私有工具 ----

    /**
     * 加载任务并校验可见性。
     * @param requireAssignable true=要求 assignee=me（写操作）；false=允许 view-all 看别人（读操作）。
     */
    private TodoTask loadAndAuthorize(Long id, boolean requireAssignable) {
        Long me = perm.currentUserId();
        boolean admin = perm.has("todo:task:view-all");
        return taskRepo.findById(id)
                .filter(t -> admin || me.equals(t.getAssigneeId()))
                .filter(t -> !requireAssignable || admin || me.equals(t.getAssigneeId()))
                .orElseThrow(() -> new BizException(404, "Task not found"));
    }

    /** 把 dto 补齐 assigneeName / createdByName（避免前端 join user 表）。 */
    private TodoTaskDto enrich(TodoTaskDto dto, List<Long> tagIds, List<String> tagNames) {
        Map<Long, String> nameById = new HashMap<>();
        Set<Long> ids = new HashSet<>();
        if (dto.getAssigneeId() != null) ids.add(dto.getAssigneeId());
        if (dto.getCreatedBy() != null)   ids.add(dto.getCreatedBy());
        if (!ids.isEmpty()) {
            userRepo.findAllById(ids).forEach(u -> nameById.put(u.getId(), displayNameOf(u)));
        }
        dto.setAssigneeName(nameById.get(dto.getAssigneeId()));
        dto.setCreatedByName(nameById.get(dto.getCreatedBy()));
        dto.setTagIds(tagIds);
        dto.setTagNames(tagNames);
        return dto;
    }

    /** 批量回填 tagIds/tagNames/assigneeName/createdByName（避免列表 N+1）。 */
    private List<TodoTaskDto> attachTagsAndNames(List<TodoTask> tasks) {
        if (tasks.isEmpty()) return List.of();
        List<Long> ids = tasks.stream().map(TodoTask::getId).toList();
        List<Object[]> tagRows = taskTagRepo.findDictItemIdsByTaskIds(ids);
        Map<Long, List<Long>> byTask = new HashMap<>();
        for (Object[] r : tagRows) {
            byTask.computeIfAbsent((Long) r[0], k -> new ArrayList<>()).add((Long) r[1]);
        }
        Set<Long> allTagIds = tagRows.stream().map(r -> (Long) r[1]).collect(Collectors.toSet());
        // 标签名从字典条目解析。旧实现查的是 sys_todo_tag_legacy —— V43 后该表恒为空，
        // 导致所有 tagNames 都是 null，标签列永远不显示。
        Map<Long, String> tagNameById = tagService.labelMap(allTagIds);
        Set<Long> userIds = new HashSet<>();
        for (TodoTask t : tasks) {
            if (t.getAssigneeId() != null) userIds.add(t.getAssigneeId());
            if (t.getCreatedBy() != null)   userIds.add(t.getCreatedBy());
        }
        Map<Long, String> userNameById = new HashMap<>();
        if (!userIds.isEmpty()) {
            userRepo.findAllById(userIds).forEach(u -> userNameById.put(u.getId(), displayNameOf(u)));
        }
        List<TodoTaskDto> out = new ArrayList<>(tasks.size());
        for (TodoTask t : tasks) {
            List<Long> tagIds = byTask.getOrDefault(t.getId(), List.of());
            List<String> tagNames = tagIds.stream().map(tagNameById::get).filter(Objects::nonNull).toList();
            TodoTaskDto dto = TodoTaskDto.fromEntity(t, tagIds, tagNames);
            dto.setAssigneeName(userNameById.get(t.getAssigneeId()));
            dto.setCreatedByName(userNameById.get(t.getCreatedBy()));
            out.add(dto);
        }
        return out;
    }

    /**
     * 全量替换任务的标签：先清关联行，再按名 upsert 到 todo_tag 字典并写关联。
     *
     * <p><b>这里是 V43 遗留 bug 的核心。</b>旧实现是「往 sys_todo_tag 插一条拿 id，
     * 再把 id 写进 sys_todo_task_tag.dict_item_id」—— 但 V43 已经把
     * {@code dict_item_id} 的外键改指到 {@code sys_dict_item(id)}，而
     * {@code sys_todo_tag} 表已被 DROP、由 V44 建的 {@code sys_todo_tag_legacy} 占位。
     * 于是插进占位表拿到的 id 几乎必然落在 sys_dict_item 的 id 区间之外 →
     * {@code sys_todo_task_tag_dict_item_id_fkey} 外键违例 → 新建带标签的待办直接 500。
     *
     * <p>现在统一走字典 upsert，id 一定落在 sys_dict_item 内，外键成立。
     *
     * @param tagNames 标签名列表（前端 el-select 的 value 就是标签名）
     */
    private void replaceTags(Long taskId, List<String> tagNames) {
        taskTagRepo.deleteByTaskId(taskId);
        taskTagRepo.flush();
        if (tagNames == null || tagNames.isEmpty()) return;
        Set<String> normalized = tagNames.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String name : normalized) {
            long dictItemId = tagService.upsertIdByName(name);
            taskTagRepo.save(TodoTaskTag.builder().taskId(taskId).dictItemId(dictItemId).build());
        }
    }

    /** 校验 parent 归属 + 状态（PENDING）。excludeId 用于排除自身（避免自引用）。 */
    private Long validateParent(Long assigneeId, Long parentId, Long excludeId) {
        if (parentId == null) return null;
        if (excludeId != null && parentId.equals(excludeId)) {
            throw new BizException(400, "Task cannot be its own parent");
        }
        TodoTask p = taskRepo.findByIdAndAssigneeId(parentId, assigneeId)
                .orElseThrow(() -> new BizException(400, "Parent task not found"));
        if (!TodoTask.Status.PENDING.name().equals(p.getStatus())) {
            throw new BizException(400, "Parent task must be PENDING");
        }
        return parentId;
    }

    /** 创建时决定 assignee：admin/assign-perm 或 self。 */
    private Long resolveAssigneeOnCreate(Long reqAssigneeId, Long me) {
        if (reqAssigneeId == null) return me;
        // admin 或自己 → 接受；其他人 → 强制回退到 me
        if (reqAssigneeId.equals(me)) return me;
        if (perm.has("todo:task:assign")) {
            // 校验目标用户存在且启用
            SysUser u = userRepo.findById(reqAssigneeId)
                    .orElseThrow(() -> new BizException(400, "Assignee not found"));
            if (u.getStatus() == null || u.getStatus() == 0) {
                throw new BizException(400, "Assignee is disabled");
            }
            return reqAssigneeId;
        }
        log.warn("[todo] create: user={} 试图给非自身指定 assignee={}，已忽略（保持自身）", me, reqAssigneeId);
        return me;
    }

    /** 重指派 perm：admin (todo:task:assign) 或任务创建者 (createdBy = me)。 */
    private boolean canReassign(TodoTask t, Long me) {
        if (perm.has("todo:task:assign")) return true;
        return t.getCreatedBy() != null && t.getCreatedBy().equals(me);
    }

    private String normalizeEnum(String field, String value, Class<? extends Enum<?>> type, Enum<?> dflt) {
        if (value == null || value.isBlank()) {
            return dflt == null ? null : dflt.name();
        }
        try {
            return Enum.valueOf((Class<Enum>) type.asSubclass(Enum.class), value.toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            throw new BizException(400, "Invalid " + field + ": " + value);
        }
    }

    private String displayNameOf(SysUser u) {
        if (u.getDisplayName() != null && !u.getDisplayName().isBlank()) return u.getDisplayName();
        return u.getUsername();
    }

    /**
     * 循环规则日期推进：
     *  - DAILY   : base + 1 天
     *  - WEEKLY  : 从 base 次日起，找下一个匹配 dow 的日期（dow=null → 用 base 当天 dow）
     *  - MONTHLY : base +1 月，日 = min(dom, 当月天数)；dom=0 → 取当月最后一天；dom=null → 用 base 当天 dom
     *  - YEARLY  : base +1 年，月日按 recurrence_month/recurrence_dom；非闰年 2/29 → 2/28
     *
     * 边界处理：
     *  - MONTHLY dom=31 → 4 月生成 4/30；YEARLY 2/29 → 平年退 2/28
     */
    private static LocalDateTime advance(LocalDateTime base, String recurrence,
                                       Integer dow, Integer dom, Integer month) {
        LocalDate baseDate = base.toLocalDate();
        LocalTime baseTime = base.toLocalTime();
        return switch (TodoTask.Recurrence.valueOf(recurrence)) {
            case DAILY -> base.plusDays(1);
            case WEEKLY -> {
                int target = dow != null ? dow : baseDate.getDayOfWeek().getValue();
                DayOfWeek targetDow = DayOfWeek.of(target);
                // 从 base 次日起推进：不允许「同一天重复触发」
                LocalDate candidate = baseDate.plusDays(1);
                while (candidate.getDayOfWeek() != targetDow) {
                    candidate = candidate.plusDays(1);
                }
                yield candidate.atTime(baseTime);
            }
            case MONTHLY -> {
                YearMonth ym = YearMonth.from(baseDate).plusMonths(1);
                int target;
                if (dom == null) {
                    target = baseDate.getDayOfMonth();
                } else if (dom == 0) {
                    target = ym.lengthOfMonth(); // 「最后一天」哨兵值
                } else {
                    target = dom;
                }
                int actualDom = Math.min(target, ym.lengthOfMonth());
                yield ym.atDay(actualDom).atTime(baseTime);
            }
            case YEARLY -> {
                int targetMonth = month != null ? month : baseDate.getMonthValue();
                int targetDom = dom != null && dom != 0 ? dom : baseDate.getDayOfMonth();
                YearMonth ym = YearMonth.of(baseDate.getYear() + 1, targetMonth);
                int actualDom = Math.min(targetDom, ym.lengthOfMonth()); // 2/29 → 2/28 平年
                yield ym.atDay(actualDom).atTime(baseTime);
            }
            default -> base;
        };
    }

    /**
     * 校验循环规则配置完整性，返回 [dow, dom, month]（可为 null）。
     * 规则：
     *  - WEEKLY  + dow null → 400（前端必须传）
     *  - MONTHLY + dom null → 400（前端必须传；可用 0 表示「最后一天」）
     *  - YEARLY  + month 或 dom 任一 null → 400
     *  - DAILY/NONE → 三个都返回 null
     */
    private Integer[] resolveRecurrenceConfig(String recurrence, Integer dow, Integer dom, Integer month) {
        return switch (TodoTask.Recurrence.valueOf(recurrence)) {
            case NONE, DAILY -> new Integer[]{null, null, null};
            case WEEKLY -> {
                if (dow == null || dow < 1 || dow > 7) {
                    throw new BizException(400, "请设置周几（1-7）");
                }
                yield new Integer[]{dow, null, null};
            }
            case MONTHLY -> {
                if (dom == null || (dom != 0 && (dom < 1 || dom > 31))) {
                    throw new BizException(400, "请设置每月几号（1-31，0 表示最后一天）");
                }
                yield new Integer[]{null, dom, null};
            }
            case YEARLY -> {
                if (month == null || month < 1 || month > 12) {
                    throw new BizException(400, "请设置月份（1-12）");
                }
                if (dom == null || (dom != 0 && (dom < 1 || dom > 31))) {
                    throw new BizException(400, "请设置日期（1-31）");
                }
                yield new Integer[]{null, dom, month};
            }
        };
    }
}