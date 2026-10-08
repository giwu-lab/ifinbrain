package com.anomalyco.ifinbrain.web.todo.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.entity.SysUser;
import com.anomalyco.ifinbrain.web.repository.SysUserRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.anomalyco.ifinbrain.web.service.SysDeptService;
import com.anomalyco.ifinbrain.web.todo.dto.TodoAssigneeOption;
import com.anomalyco.ifinbrain.web.todo.dto.TodoDashboardItem;
import com.anomalyco.ifinbrain.web.todo.dto.TodoDashboardResponse;
import com.anomalyco.ifinbrain.web.todo.dto.TodoInsightResponse;
import com.anomalyco.ifinbrain.web.todo.dto.TodoQuadrant;
import com.anomalyco.ifinbrain.web.todo.dto.TodoQuadrantBucket;
import com.anomalyco.ifinbrain.web.todo.dto.TodoQuadrantResponse;
import com.anomalyco.ifinbrain.web.todo.dto.TodoStatsDto;
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
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.data.jpa.domain.Specification;
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
    private final SysDeptService deptService;
    private final PermissionChecker perm;

    // ---- 列表 / 详情 ----

    /**
     * 列表页排序，下推到 SQL。
     * 未完成优先 → 有 dueAt 的排前且升序 → 无 dueAt 殿后 → id 倒序（最新在前）。
     *
     * <p>两个 CASE WHEN 片段对应旧实现里 memory sort 的两条规则；用 JpaSort.unsafe
     * 是因为「status 字母序」虽然碰巧等价于「未完成优先」，但意图不清晰、易被后续
     * 改动踩坏，所以显式写成 JPQL。
     *
     * <p>JpaSort.unsafe 只有「全同方向」的重载，故前三个升序表达式用
     * unsafe(Direction.ASC, ...)，id 倒序再 and() 链式追加。
     */
    private static final Sort LIST_SORT = JpaSort
            .unsafe(Direction.ASC,
                    "(case when status = 'COMPLETED' then 1 else 0 end)",
                    "(case when dueAt is null then 1 else 0 end)",
                    "dueAt")
            .and(Sort.by(Order.desc("id")));

    /** 单页上限，防止 ?size=100000 拖垮数据库。 */
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * 列表分页 + 筛选。筛选条件全部下推到 SQL 的 WHERE，total 交由 Spring Data 统计。
     *
     * <p>旧实现是「先查一页 → 在 Java 里 filter → 返回 filtered.size() 当 total」，
     * 导致两个缺陷：翻页漏数据（第 2 页可能空但实际还有匹配）、total 只统计当页。
     * 见 {@link #buildListSpec} 与 {@link #appendMissingParents}。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> page(int page, int size, String status, String priority, String keyword) {
        Long me = perm.currentUserId();
        boolean adminViewAll = perm.has("todo:task:view-all");

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<TodoTask> result = taskRepo.findAll(
                buildListSpec(me, adminViewAll, status, priority, keyword),
                PageRequest.of(Math.max(0, page - 1), safeSize, LIST_SORT));

        List<TodoTaskDto> items = attachTagsAndNames(appendMissingParents(result.getContent(), adminViewAll, me));
        Map<String, Object> resp = new HashMap<>();
        resp.put("items", items);
        // 真实匹配总数；补进来的父任务行不计入，分页器页数才不会算错。
        resp.put("total", result.getTotalElements());
        resp.put("page", page);
        resp.put("size", safeSize);
        resp.put("adminViewAll", adminViewAll);
        return resp;
    }

    /**
     * 列表筛选条件下推到 SQL。
     *
     * <ul>
     *   <li>assignee 隔离：非 view-all 恒按 assigneeId = me。</li>
     *   <li>status / priority 转大写后精确匹配，沿用旧的 equalsIgnoreCase 语义；
     *       非法值自然查出空集，不抛 400 —— 保持列表接口的宽容行为。</li>
     *   <li>keyword 对 title / description 做小写 LIKE，保留旧的中文大小写不敏感匹配。</li>
     * </ul>
     */
    private Specification<TodoTask> buildListSpec(Long me, boolean adminViewAll,
                                                  String status, String priority, String keyword) {
        Specification<TodoTask> spec = (root, q, cb) ->
                adminViewAll ? cb.conjunction() : cb.equal(root.get("assigneeId"), me);
        if (status != null && !status.isBlank()) {
            String v = status.trim().toUpperCase(Locale.ROOT);
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), v));
        }
        if (priority != null && !priority.isBlank()) {
            String v = priority.trim().toUpperCase(Locale.ROOT);
            spec = spec.and((root, q, cb) -> cb.equal(root.get("priority"), v));
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
            spec = spec.and((root, q, cb) -> cb.or(
                    cb.like(cb.lower(root.get("title")), kw),
                    cb.like(cb.lower(cb.coalesce(root.get("description"), "")), kw)));
        }
        return spec;
    }

    /**
     * 父任务补全：当页里出现子任务、但父任务本身不在当页时，把缺失的祖先一并带出，
     * 插在当页任务之前（保持「父在前 → 子紧随」的两级视觉）。
     *
     * <p>为什么必须有：前端 sortHierarchical() 按 parentId 挂树，父任务缺席时
     * visit(null) 走不到子任务，<b>子任务会直接凭空消失</b>。分页真正生效后父子
     * 落在不同页的概率大增，所以这里补的是整条祖先链而非仅直接父。
     *
     * <p>补出来的行不计入 total：total 仍是筛选命中的精确条数，因此某一页显示的
     * 行数可能略多于 size，但分页器总页数是正确的。
     */
    private List<TodoTask> appendMissingParents(List<TodoTask> pageItems, boolean adminViewAll, Long me) {
        if (pageItems.isEmpty()) return pageItems;

        Set<Long> visited = pageItems.stream().map(TodoTask::getId).collect(Collectors.toSet());
        Set<Long> frontier = pageItems.stream()
                .map(TodoTask::getParentId)
                .filter(Objects::nonNull)
                .filter(id -> visited.add(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<Long, TodoTask> missing = new HashMap<>();
        while (!frontier.isEmpty()) {
            List<TodoTask> parents = taskRepo.findAllById(frontier).stream()
                    .filter(t -> adminViewAll || me.equals(t.getAssigneeId()))
                    .toList();
            frontier = new LinkedHashSet<>();
            for (TodoTask p : parents) {
                missing.putIfAbsent(p.getId(), p);
                // 继续上溯祖父，支持多级子任务
                Long grand = p.getParentId();
                if (grand != null && visited.add(grand)) {
                    frontier.add(grand);
                }
            }
        }
        if (missing.isEmpty()) return pageItems;

        // 把补出来的祖先按「根在前」排成链，整体插到当页任务之前。
        List<TodoTask> chain = new ArrayList<>(missing.size());
        Set<Long> remaining = new HashSet<>(missing.keySet());
        boolean progress = true;
        while (!remaining.isEmpty() && progress) {
            progress = false;
            for (Long id : new ArrayList<>(remaining)) {
                TodoTask t = missing.get(id);
                if (t.getParentId() == null || !remaining.contains(t.getParentId())) {
                    chain.add(t);
                    remaining.remove(id);
                    progress = true;
                }
            }
        }
        // 理论上 validateParent 已挡住环；真出现也别让整条链消失，按 id 兜底追加。
        missing.values().stream()
                .filter(t -> remaining.contains(t.getId()))
                .sorted(Comparator.comparing(TodoTask::getId))
                .forEach(chain::add);

        List<TodoTask> merged = new ArrayList<>(chain);
        merged.addAll(pageItems);
        return merged;
    }

    @Transactional(readOnly = true)
    public TodoTaskDto detail(Long id) {
        perm.require("todo:task:view");
        TodoTask t = loadForRead(id);
        List<Long> tagIds = taskTagRepo.findDictItemIdsByTaskId(t.getId());
        List<String> tagNames = tagService.labelMap(tagIds).values().stream().toList();
        return enrich(attachTagsAndNames(List.of(t)).get(0), tagIds, tagNames);
    }

    /**
     * 直接子任务明细 —— 父任务卡片「展开子任务」的数据源（默认不展开，点开才拉）。
     *
     * <p>鉴权只看父任务：能读父任务就能读它的子树。子任务本身可能派给别人，
     * 逐条按 assignee 过滤会让「父任务可见但展开后少了几条」这种界面自相矛盾。
     * 每行的 canEdit/canComplete 仍由 fillRowPermissions 按「执行人 × 部门」算，
     * 所以跨部门子任务的勾选框照样是灰的。
     *
     * <p>孙任务不在这里返回 —— 树形展开由前端在点击孙任务时再调一次本接口。
     */
    @Transactional(readOnly = true)
    public List<TodoTaskDto> children(Long id) {
        perm.require("todo:task:view");
        TodoTask parent = loadForRead(id);
        List<TodoTask> kids = taskRepo.findChildren(parent.getId());
        if (kids.isEmpty()) return List.of();
        return attachTagsAndNames(kids);
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
                .estimateMinutes(req.getEstimateMinutes())
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
        TodoTask t = loadForWrite(id);

        if (req.getTitle() != null) t.setTitle(req.getTitle().trim());
        if (req.getDescription() != null) t.setDescription(req.getDescription());
        if (req.getPriority() != null) {
            t.setPriority(normalizeEnum("priority", req.getPriority(), TodoTask.Priority.class, null));
        }
        if (req.getStatus() != null) {
            t.setStatus(normalizeEnum("status", req.getStatus(), TodoTask.Status.class, null));
            if (TodoTask.Status.COMPLETED.name().equals(t.getStatus()) && t.getCompletedAt() == null) {
                // 编辑表单的状态单选和勾选完成是同一个语义出口，必须同样拦，
                // 否则用户从编辑弹窗把状态改成「已完成」就能绕过 complete() 的校验。
                assertNoPendingChildren(t);
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
        if (req.getEstimateMinutes() != null) t.setEstimateMinutes(req.getEstimateMinutes());
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
     * 「子任务未完成，父任务不能勾选完成」的唯一判定点 —— complete() 和 update() 都走这里。
     *
     * <p>只查直接子任务：规则沿树传递（A 完成需 B 完成，B 完成需 C 完成 ⇒ A 间接要求 C 完成），
     * 逐层拦即覆盖整棵子树。只在 status 由非 COMPLETED 切向 COMPLETED 时才有意义，
     * 已完成的任务被再次写入、或把已完成任务改回 PENDING 都不受影响。
     */
    private void assertNoPendingChildren(TodoTask t) {
        if (t.getId() == null) return;
        long pending = taskRepo.countByParentIdAndStatus(t.getId(), TodoTask.Status.PENDING.name());
        if (pending > 0) {
            throw new BizException(400, "还有 " + pending + " 个子任务未完成，请先完成子任务");
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
        TodoTask t = loadForWrite(id);
        // 拦截必须早于 setStatus，更早于 recurrence spawn —— 否则循环任务会先复制出一条
        // 新 PENDING 才被拒，用户看到「没勾上却又多了一条」。
        assertNoPendingChildren(t);

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
        TodoTask t = loadForWrite(id);
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

    // ================= 四象限视图（仪表盘「我的任务」） =================

    /** 四象限单次取数上限，防止异常数据量把整页撑爆。 */
    private static final int QUADRANT_MAX = 500;

    /**
     * 「四象限」tab 数据源：统计条 + 四个格子。
     *
     * <p>只取 assignee = me 的 PENDING 任务 —— 与 dashboard() 保持同一口径，
     * 持 view-all 也不混入他人任务（「我的任务」混入别人的就没意义了）。
     */
    @Transactional(readOnly = true)
    public TodoQuadrantResponse quadrant(int limit) {
        Long me = perm.currentUserId();
        LocalDateTime now = LocalDateTime.now();
        int cap = limit <= 0 ? 200 : Math.min(limit, QUADRANT_MAX);

        List<TodoTask> pending = taskRepo.findPendingForQuadrant(me, PageRequest.of(0, cap));
        List<TodoTaskDto> dtos = attachTagsAndNames(pending);

        // 四个格子恒定存在，空象限返回空列表而不是缺席，避免前端 2×2 网格塌格
        Map<TodoQuadrant, List<TodoTaskDto>> grouped = new EnumMap<>(TodoQuadrant.class);
        for (TodoQuadrant q : TodoQuadrant.values()) grouped.put(q, new ArrayList<>());

        for (int i = 0; i < dtos.size(); i++) {
            TodoTask t = pending.get(i);
            TodoTaskDto dto = dtos.get(i);
            TodoQuadrant q = TodoQuadrant.of(t.getPriority(), t.getDueAt(), now);
            dto.setQuadrant(q.getKey());
            // childTotal / childDone / pendingChildCount 已由 attachTagsAndNames → fillRowPermissions 填好
            grouped.get(q).add(dto);
        }

        List<TodoQuadrantBucket> buckets = new ArrayList<>(TodoQuadrant.values().length);
        for (TodoQuadrant q : TodoQuadrant.values()) {
            List<TodoTaskDto> list = grouped.get(q);
            buckets.add(TodoQuadrantBucket.builder()
                    .key(q.getKey()).count(list.size()).tasks(list).build());
        }

        return TodoQuadrantResponse.builder()
                .stats(loadStats(me, now))
                .quadrants(buckets)
                .build();
    }

    /**
     * 「今天」tab 数据源：逾期 + 今日到期的 PENDING 任务（按截止时间升序）。
     *
     * <p>逾期的一并返回，是因为"今天该做什么"这个问题里逾期项的优先级最高，
     * 让用户单独切一次才能看到它们是自找麻烦。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> today(int limit) {
        Long me = perm.currentUserId();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endOfToday = now.toLocalDate().atTime(23, 59, 59, 999_000_000);
        int cap = limit <= 0 ? 50 : Math.min(limit, 200);

        List<TodoTask> rows = taskRepo.findTodayOrOverdue(me, endOfToday, PageRequest.of(0, cap));
        List<TodoTaskDto> dtos = attachTagsAndNames(rows);

        for (int i = 0; i < dtos.size(); i++) {
            TodoTask t = rows.get(i);
            TodoTaskDto dto = dtos.get(i);
            dto.setQuadrant(TodoQuadrant.of(t.getPriority(), t.getDueAt(), now).getKey());
            // childTotal / childDone / pendingChildCount 已由 attachTagsAndNames → fillRowPermissions 填好
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("items", dtos);
        resp.put("stats", loadStats(me, now));
        return resp;
    }

    /** 洞察视图允许的天数窗口，超出则收敛到两端 —— 防止前端传 days=99999 拖垮聚合。 */
    private static final int INSIGHT_MIN_DAYS = 7;
    private static final int INSIGHT_MAX_DAYS = 90;

    /**
     * 洞察视图：时间趋势 + 象限结构 + 行动建议。
     *
     * <p>口径说明（这几条决定了数字之间是否自洽）：
     * <ul>
     *   <li><b>完成率分母 = 窗口内新建数</b>，不是全量任务数。用全量当分母时，
     *       老任务会永久压低完成率，看不出这周有没有进步。</li>
     *   <li><b>逾期率分母 = 当前未完成总数</b>（全量，不受窗口影响），因为"我手上现在
     *       有多少事已经过期"是个存量问题，与时间窗口无关。</li>
     *   <li><b>平均完成耗时只在两端时间都存在时统计</b>。没有 createdAt 的历史数据
     *       参与计算会把均值拉到极小值。</li>
     *   <li>趋势序列<b>补齐缺失日</b>：哪天没完成任务，折线必须仍占一个 0 点，
     *       否则日期轴会跳格，把"连休三天"显示成"当天完成 3 件"。</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public TodoInsightResponse insight(int days) {
        Long me = perm.currentUserId();
        LocalDateTime now = LocalDateTime.now();
        int window = Math.max(INSIGHT_MIN_DAYS, Math.min(days <= 0 ? INSIGHT_MIN_DAYS : days, INSIGHT_MAX_DAYS));
        LocalDate from = now.toLocalDate().minusDays(window - 1L);
        LocalDateTime fromStart = from.atStartOfDay();

        List<TodoTask> all = taskRepo.findAllForInsight(me);

        // —— 趋势：按天分桶 ——
        // LinkedHashMap 而非 HashMap：保持插入顺序 = 日期升序。HashMap 的迭代顺序由
        // hash 决定，出来的折线会随机打乱日期，前端 x 轴直接变成乱的。
        Map<LocalDate, int[]> bucket = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(now.toLocalDate()); d = d.plusDays(1)) {
            bucket.put(d, new int[]{0, 0}); // {completed, created}
        }
        int createdInRange = 0, completedInRange = 0;
        long cycleMillisSum = 0;
        int cycleSamples = 0;
        long estimateSum = 0;
        int estimatedCount = 0, totalCount = all.size();

        for (TodoTask t : all) {
            LocalDateTime created = t.getCreatedAt();
            if (created != null && !created.isBefore(fromStart)) {
                int[] b = bucket.get(created.toLocalDate());
                if (b != null) {
                    b[1]++;
                    createdInRange++;
                }
            }
            if (t.getEstimateMinutes() != null) {
                estimateSum += t.getEstimateMinutes();
                estimatedCount++;
            }
            if (TodoTask.Status.COMPLETED.name().equals(t.getStatus())) {
                LocalDateTime done = t.getCompletedAt();
                if (done != null && !done.isBefore(fromStart)) {
                    int[] b = bucket.get(done.toLocalDate());
                    if (b != null) {
                        b[0]++;
                        completedInRange++;
                    }
                    // 耗时只统计窗口内完成的，且两端时间都齐
                    if (created != null && done.isAfter(created)) {
                        cycleMillisSum += java.time.Duration.between(created, done).toMillis();
                        cycleSamples++;
                    }
                }
            }
        }

        List<TodoInsightResponse.TrendPoint> trend = new ArrayList<>(bucket.size());
        for (Map.Entry<LocalDate, int[]> e : bucket.entrySet()) {
            trend.add(TodoInsightResponse.TrendPoint.builder()
                    .date(e.getKey()).completed(e.getValue()[0]).created(e.getValue()[1]).build());
        }

        // —— 象限结构：只看未完成的，已完成的不该继续占结构比例 ——
        Map<TodoQuadrant, long[]> quadCount = new EnumMap<>(TodoQuadrant.class);
        Map<TodoQuadrant, long[]> quadEstimate = new EnumMap<>(TodoQuadrant.class);
        for (TodoQuadrant q : TodoQuadrant.values()) {
            quadCount.put(q, new long[]{0});
            quadEstimate.put(q, new long[]{0});
        }
        int pending = 0, overdue = 0;
        for (TodoTask t : all) {
            if (TodoTask.Status.COMPLETED.name().equals(t.getStatus())) continue;
            pending++;
            if (TodoQuadrant.isOverdue(t.getDueAt(), now)) overdue++;
            TodoQuadrant q = TodoQuadrant.of(t.getPriority(), t.getDueAt(), now);
            if (q == null) continue;
            quadCount.get(q)[0]++;
            if (t.getEstimateMinutes() != null) quadEstimate.get(q)[0] += t.getEstimateMinutes();
        }

        List<TodoInsightResponse.QuadrantShare> shares = new ArrayList<>(TodoQuadrant.values().length);
        for (TodoQuadrant q : TodoQuadrant.values()) {
            shares.add(TodoInsightResponse.QuadrantShare.builder()
                    .key(q.getKey())
                    .count(quadCount.get(q)[0])
                    .estimateMinutes(quadEstimate.get(q)[0])
                    .build());
        }

        double completionRate = createdInRange == 0 ? 0d : (double) completedInRange / createdInRange;
        double overdueRate = pending == 0 ? 0d : (double) overdue / pending;
        Double avgCycleHours = cycleSamples == 0 ? null : (double) cycleMillisSum / cycleSamples / 3_600_000d;

        return TodoInsightResponse.builder()
                .days(window)
                .completionRate(completionRate)
                .overdueRate(overdueRate)
                .avgCycleHours(avgCycleHours)
                .totalEstimateHours(estimateSum / 60d)
                .estimatedCoverage(totalCount == 0 ? 0d : (double) estimatedCount / totalCount)
                .backlog(pending)
                .createdInRange(createdInRange)
                .completedInRange(completedInRange)
                .trend(trend)
                .quadrants(shares)
                .suggestions(buildSuggestions(pending, overdue, quadCount, completedInRange, window))
                .build();
    }

    /**
     * 行动建议：只给最值得动手的 3 条，按严重度降序。
     *
     * <p>刻意不铺满 5 条 —— 洞察页的价值在于"看完知道先做哪一件"，列太多等于没给结论。
     */
    private List<TodoInsightResponse.Suggestion> buildSuggestions(
            int pending, int overdue, Map<TodoQuadrant, long[]> quadCount,
            int completedInRange, int days) {
        List<TodoInsightResponse.Suggestion> out = new ArrayList<>();
        long q1 = quadCount.get(TodoQuadrant.Q1)[0];
        long q2 = quadCount.get(TodoQuadrant.Q2)[0];
        long q3 = quadCount.get(TodoQuadrant.Q3)[0];
        long q4 = quadCount.get(TodoQuadrant.Q4)[0];

        if (overdue > 0) {
            out.add(sug("overdue",
                    "有 " + overdue + " 个任务已逾期",
                    "逾期任务的优先级会随时间自动升高，先集中清掉这批，再谈其他安排。",
                    90 + Math.min(overdue, 10)));
        }
        if (q1 > 3) {
            out.add(sug("backlog",
                    "Q1 积压了 " + q1 + " 个重要且紧急的任务",
                    "Q1 超过 3 个通常意味着重要的事没有及时排期。挑最早的那个先做，其余重新评估截止日期。",
                    70 + (int) Math.min(q1, 20)));
        }
        if (q4 >= 5) {
            out.add(sug("q4",
                    "Q4 有 " + q4 + " 个可以删掉的任务",
                    "不重要也不紧急的事堆着不做，本身就是一种消耗。逐条问一句：不做会怎样？",
                    50 + (int) Math.min(q4, 20)));
        }
        if (completedInRange == 0 && pending > 0) {
            out.add(sug("backlog",
                    "最近 " + days + " 天没有完成任何任务",
                    "全部时间都在新增、没有任何收尾，积压会持续扩大。试着今天只完成一件、不新增。",
                    85));
        } else if (q3 > q1) {
            out.add(sug("q3",
                    "Q3（委托做）比 Q1 还多",
                    "大量紧急不重要的事说明本该分出去的事还压在自己手上。挑一条真正交给别人。",
                    40));
        } else if (q2 > q1 * 3 && q2 > 0) {
            out.add(sug("q2",
                    "Q2（计划做）远多于 Q1",
                    "重要不紧急的事长期不推进会变质为紧急。给它们排一个固定时段，而不是等有空。",
                    30));
        }
        if (out.isEmpty()) {
            out.add(sug("balanced",
                    "当前节奏比较健康",
                    "没有明显积压或逾期，保持现有的处理顺序即可。",
                    10));
        }
        out.sort((a, b) -> Integer.compare(b.getSeverity(), a.getSeverity()));
        return out.size() > 3 ? new ArrayList<>(out.subList(0, 3)) : out;
    }

    private TodoInsightResponse.Suggestion sug(String kind, String title, String detail, int severity) {
        return TodoInsightResponse.Suggestion.builder()
                .kind(kind).title(title).detail(detail).severity(severity).build();
    }

    /** 统计条：一次算齐四个数字，避免前端分四个请求导致数字不一致。 */
    private TodoStatsDto loadStats(Long me, LocalDateTime now) {
        LocalDateTime startOfDay = now.toLocalDate().atStartOfDay();
        long pending = taskRepo.countByAssigneeIdAndStatus(me, TodoTask.Status.PENDING.name());
        long completed = taskRepo.countByAssigneeIdAndStatus(me, TodoTask.Status.COMPLETED.name());
        long total = taskRepo.countByAssigneeId(me);
        return TodoStatsDto.builder()
                .pending(pending)
                .dueToday(taskRepo.countDueToday(me, startOfDay, startOfDay.plusDays(1)))
                .overdue(taskRepo.countOverdue(me, startOfDay))
                .completed(completed)
                .total(total)
                .completionRate(total == 0 ? 0d : (double) completed / total)
                .build();
    }

    /**
     * 批量取子任务进度 —— 返回 taskId → {总数, 已完成数}。
     * 必须批量：卡片列表逐行查子任务的话，一屏 20 张卡就是 20 次查询。
     *
     * <p>收集的是这批任务**自身的 id**（要查「谁是我的孩子」），不是它们的 parentId。
     * 早先误用 {@code getParentId()}，只在「父子恰好同批返回」时才对：父任务 PENDING、
     * 子任务一个已完成一个未完成时，列表里只有父任务和那个未完成子任务，
     * 收集到的是子任务的 parentId，父任务反而查不到自己的进度，显示成「子任务 0/1」
     * 而实际是 1/2。完成态过滤、象限过滤都会踩中。
     */
    private Map<Long, int[]> loadChildProgress(List<TodoTask> tasks) {
        Set<Long> ids = tasks.stream()
                .map(TodoTask::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        Map<Long, int[]> map = new HashMap<>();
        for (Object[] row : taskRepo.countChildProgress(ids)) {
            Long pid = (Long) row[0];
            int total = ((Number) row[1]).intValue();
            int done = row[2] == null ? 0 : ((Number) row[2]).intValue();
            map.put(pid, new int[]{total, done});
        }
        return map;
    }

    // ================= 仪表盘（旧 widget，保留兼容） =================

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

        /**
     * 我的 PENDING 任务数 —— 顶栏通知红点用。
     *
     * <p>为什么单独开一个端点，而不是让前端调 {@link #today} 再从返回体里取
     * {@code stats.pending}：today() 会把最多 50 条任务实体查出来再统计，
     * 而红点只需要一个数字 —— 每次登录都白拉 50 行实体不划算。
     * 形态与 flowTask 的 {@code /design/flow-tasks/pending/count} 对齐。
     *
     * <p>口径与 {@link #dashboard} 的「我的任务」一致：恒按 assignee = me，
     * <b>不</b>因持有 {@code todo:task:view-all} 就变成全员计数
     * （红点代表"我的活儿还有几件"，混入别人的任务会让数字失去意义）。
     */
    @Transactional(readOnly = true)
    public long pendingCount() {
        perm.require("todo:task:view");
        Long me = perm.currentUserId();
        return taskRepo.countByAssigneeIdAndStatus(me, TodoTask.Status.PENDING.name());
    }

    // ---- 跨模块：附件服务需要校验任务可见性 ----

    /**
     * 附件读：任务可见即可（自己的，或持 view-all 的跨部门任务）。
     * 供 TodoAttachmentService 的 list / download / delete 可见性校验使用。
     */
    @Transactional(readOnly = true)
    public TodoTask loadForAttachmentRead(Long taskId) {
        perm.require("todo:task:view");
        return loadForRead(taskId);
    }

    /**
     * 附件写：任务必须可写，即执行人在本部门子树内。
     * 跨部门任务在此返回 403 —— 上传附件同样是写操作，
     * 否则"跨部门只读"就留了一个往别人任务里塞文件的口子。
     */
    @Transactional(readOnly = true)
    public TodoTask loadForAttachmentWrite(Long taskId) {
        perm.require("todo:task:view");
        return loadForWrite(taskId);
    }

    // ---- 私有工具 ----

    // ==================== 权限判定 ====================
    //
    // 「看得见」和「改得动」在本模块里刻意是两件事：
    //
    //   view-all    跨部门**只读** —— 能看到别人乃至别的部门的任务
    //   manage-all  **限本部门子树内可写** —— 部门外的任务一律只读
    //
    // 旧实现把 view-all 同时用于读写，于是 task_manager 能硬删除任何部门的任务；
    // 更糟的是 loadAndAuthorize 里两个 filter 条件完全相同，requireAssignable
    // 参数是死代码，注释宣称的"要求 assignee=me"从未真正生效过。

    /** 读：自己的任务，或持 view-all。 */
    boolean canRead(TodoTask t) {
        Long me = perm.currentUserId();
        if (me == null) return false;
        return me.equals(t.getAssigneeId()) || perm.has("todo:task:view-all");
    }

    /** 写：自己的任务，或 manage-all 且执行人在本部门子树内。admin 豁免部门约束。 */
    boolean canWrite(TodoTask t) {
        Long me = perm.currentUserId();
        if (me == null) return false;
        if (me.equals(t.getAssigneeId())) return true;
        if (!perm.has("todo:task:manage-all")) return false;
        return inScope(writableDeptScope(), deptIdOf(t.getAssigneeId()));
    }

    /**
     * 当前用户「可写的部门集合」，整轮请求算一次复用，避免每行任务都查一遍部门树。
     *
     * @return {@code null} = 不限（admin 角色豁免）；空集 = 谁的任务都管不了（未挂部门，fail-closed）
     */
    private Set<Long> writableDeptScope() {
        if (isSuperAdmin()) return null;
        Long myDept = deptIdOf(perm.currentUserId());
        if (myDept == null) return Set.of();
        return deptService.subtreeIds(myDept);
    }

    /**
     * 任务归属是否落在可写范围内。
     * scope 为 null（admin 豁免）→ 恒真；scope 为空（未挂部门）→ 恒假（fail-closed，
     * 不能因为"查不到部门"就放行）；否则按部门树成员判定。
     */
    private static boolean inScope(Set<Long> scope, Long ownerDept) {
        if (scope == null) return true;
        if (scope.isEmpty()) return false;
        return ownerDept != null && scope.contains(ownerDept);
    }

    private boolean isSuperAdmin() {
        String roles = perm.currentRoleCodes();
        if (roles == null || roles.isBlank()) return false;
        for (String r : roles.split(",")) {
            if ("admin".equals(r.trim())) return true;
        }
        return false;
    }

    /** 用户所属部门；用户不存在或未挂部门返回 null。 */
    private Long deptIdOf(Long userId) {
        if (userId == null) return null;
        return userRepo.findById(userId).map(SysUser::getDeptId).orElse(null);
    }

    /** 读操作取任务：不可见即 404，不泄露存在性。 */
    private TodoTask loadForRead(Long id) {
        return taskRepo.findById(id)
                .filter(this::canRead)
                .orElseThrow(() -> new BizException(404, "Task not found"));
    }

    /**
     * 写操作取任务：不可写返回 403。
     *
     * <p>这里用 403 而非 404 是有意的 —— 持 view-all 的用户本来就能在列表里看到这条任务，
     * 隐藏存在性没有意义，而"跨部门任务仅可查看"才是他真正需要知道的反馈。
     */
    private TodoTask loadForWrite(Long id) {
        return taskRepo.findById(id)
                .filter(this::canWrite)
                .orElseThrow(() -> new BizException(403, "无权修改该任务：跨部门任务仅可查看"));
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
        // 执行人 → 部门，用于「本部门子树内可写」的按行判定。
        // 与展示名复用同一次 findAllById，不额外查库。
        Map<Long, Long> userDeptById = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (SysUser u : userRepo.findAllById(userIds)) {
                userNameById.put(u.getId(), displayNameOf(u));
                userDeptById.put(u.getId(), u.getDeptId());
            }
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
        fillRowPermissions(out, tasks, userDeptById);
        return out;
    }

    /**
     * 逐行计算 canEdit / canDelete / canComplete / crossDeptReadOnly。
     *
     * <p>这些是「角色权限 × 部门归属」的交集结果，无法由前端 auth.hasPerm 推断 ——
     * 一个 task_manager 在列表里同时看到本部门任务和跨部门任务，两者的可写性不同。
     *
     * @param deptByUserId 执行人 id → 部门 id（未挂部门的用户不在 map 里）
     */
    private void fillRowPermissions(List<TodoTaskDto> dtos, List<TodoTask> tasks,
                                    Map<Long, Long> deptByUserId) {
        Long me = perm.currentUserId();
        boolean viewAll = perm.has("todo:task:view-all");
        boolean manageAll = perm.has("todo:task:manage-all");
        // 整批复用同一份作用域：subtreeIds 会查一次部门表，绝不能按行调用
        Set<Long> scope = writableDeptScope();
        // 子任务进度也在这里统一填 —— 所有 DTO 出口都经过本方法，
        // 放在这里列表页才拿得到 childTotal/childDone/pendingChildCount
        Map<Long, int[]> childProgress = loadChildProgress(tasks);

        for (int i = 0; i < dtos.size(); i++) {
            TodoTask t = tasks.get(i);
            TodoTaskDto dto = dtos.get(i);
            boolean own = me != null && me.equals(t.getAssigneeId());
            boolean writable = own || (manageAll && inScope(scope, deptByUserId.get(t.getAssigneeId())));
            boolean visible = own || viewAll;
            dto.setCanEdit(writable);
            dto.setCanDelete(writable);
            // 前端置灰用：可写 且 没有未完成的直接子任务
            int[] p = childProgress.get(t.getId());
            int childTotal = p == null ? 0 : p[0];
            int childDone = p == null ? 0 : p[1];
            int pendingChildren = childTotal - childDone;
            dto.setChildTotal(childTotal);
            dto.setChildDone(childDone);
            dto.setPendingChildCount(pendingChildren);
            dto.setCanComplete(writable && pendingChildren == 0);
            // 可见但不可写 —— 前端据此把按钮置灰并提示「跨部门任务，仅可查看」
            dto.setCrossDeptReadOnly(visible && !writable);
        }
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