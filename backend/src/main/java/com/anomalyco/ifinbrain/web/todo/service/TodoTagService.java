package com.anomalyco.ifinbrain.web.todo.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.entity.SysDictItem;
import com.anomalyco.ifinbrain.web.entity.SysDictType;
import com.anomalyco.ifinbrain.web.repository.SysDictItemRepository;
import com.anomalyco.ifinbrain.web.repository.SysDictTypeRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.anomalyco.ifinbrain.web.todo.dto.TodoTagDto;
import com.anomalyco.ifinbrain.web.todo.repository.TodoTaskTagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务标签服务 —— V43 字典化重构的收尾实现。
 *
 * <h3>为什么标签住在字典里</h3>
 * <p>V43 把标签从独立表 {@code sys_todo_tag} 迁到字典：标签条目 = {@code sys_dict_item}
 * 中 {@code sys_dict_type.code='todo_tag'} 的那些行，任务关联表 {@code sys_todo_task_tag}
 * 的 {@code dict_item_id} 外键直指 {@code sys_dict_item.id}。好处是标签和发票状态、
 * 文件格式等其它枚举共用一套字典管理界面。
 *
 * <h3>这次修的是什么</h3>
 * <p>V43 之后 {@code TodoTaskService} 有一批遗留代码没跟上，留下四个洞：
 * <ol>
 *   <li>{@code GET /api/todo/tags} 压根没有 Controller → 前端进页即 404，
 *       被 GlobalExceptionHandler 兜底成「服务器内部错误」；</li>
 *   <li>{@code replaceTags} 仍然往占位表 {@code sys_todo_tag_legacy} 插一条拿 id，
 *       再把这个 id 写进外键指向 {@code sys_dict_item} 的列 → 新建带标签的待办必 500；</li>
 *   <li>{@code detail()} / {@code attachTagsAndNames()} 从空的占位表解析标签名
 *       → 标签列永远空白；</li>
 *   <li>孤儿标签清理删的是占位表 → 实际是空操作。</li>
 * </ol>
 * 本类把上述四处统一收敛到字典表，V47 随后 DROP 掉占位表。
 *
 * <h3>语义变化（重要）</h3>
 * <p>字典表<b>没有 owner_id 列</b>，所以标签从「按执行人隔离」（V43 之前每个 assignee
 * 有自己的一套同名标签）变成了 <b>全局共享</b>。这是 V43 定下的模型，本类遵循它；
 * 副作用是这些条目同时也能在「系统设置 → 字典管理」里被 dict:update 权限的用户看到和编辑。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TodoTagService {

    /** 标签字典类型编码。与 migration-prod/V43__convert_todo_tag_to_dict.sql 中的 type code 一致。 */
    public static final String TAG_DICT_CODE = "todo_tag";

    private static final int MAX_NAME_LEN = 64;

    private final SysDictTypeRepository typeRepo;
    private final SysDictItemRepository itemRepo;
    private final TodoTaskTagRepository taskTagRepo;
    private final PermissionChecker perm;

    // ================= 查询 =================

    /** 标签下拉数据源：仅启用条目，按 sort → id 升序。 */
    @Transactional(readOnly = true)
    public List<TodoTagDto> list() {
        perm.require("todo:task:view");
        Long dictId = tagDictId();
        return itemRepo.findByDictIdOrderBySortAscIdAsc(dictId).stream()
                .filter(i -> i.getStatus() == null || i.getStatus() == 1)
                .map(i -> TodoTagDto.builder().id(i.getId()).name(i.getLabel()).build())
                .toList();
    }

    /** dict_item_id → 标签名。用于列表 / 详情回填 tagNames，避免 N+1。 */
    @Transactional(readOnly = true)
    public Map<Long, String> labelMap(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<Long, String> out = new HashMap<>();
        itemRepo.findAllById(new LinkedHashSet<>(ids))
                .forEach(i -> out.put(i.getId(), i.getLabel()));
        return out;
    }

    /** 按标签名取 id，字典里没有就新建。replaceTags 走这条路。 */
    @Transactional
    public long upsertIdByName(String rawName) {
        String name = normalize(rawName);
        Long dictId = tagDictId();
        return itemRepo.findByDictIdAndLabel(dictId, name)
                .orElseGet(() -> itemRepo.save(SysDictItem.builder()
                        .dictId(dictId)
                        // code 与 label 同存标签名：(dict_id, code) 唯一约束在这里承担去重职责。
                        // 字典类型编码有 [a-z][a-z0-9_]* 的格式校验，但**条目** code 没有，
                        // 所以中文标签名可以直接当 code 用。
                        .code(name)
                        .label(name)
                        .value(name)
                        .parentId(0L)
                        .sort(0)
                        .status((short) 1)
                        .build()))
                .getId();
    }

    // ================= 写入 =================

    /** 删除标签条目。仍被任何任务引用时拒绝，避免出现指向空值的关联行。 */
    @Transactional
    public void remove(Long id) {
        perm.require("todo:task:update");
        SysDictItem item = itemRepo.findById(id)
                .orElseThrow(() -> new BizException(404, "标签不存在"));
        if (!tagDictId().equals(item.getDictId())) {
            throw new BizException(400, "该字典条目不是任务标签");
        }
        long refs = taskTagRepo.countByDictItemId(id);
        if (refs > 0) {
            throw new BizException(409, "该标签仍被 " + refs + " 个任务使用，无法删除");
        }
        itemRepo.delete(item);
    }

    /**
     * 孤儿标签清理：只处理<b>调用方指定的那几个</b> dict_item_id，删掉其中已不被任何任务引用的。
     *
     * <p><b>为什么必须收窄到候选集。</b>最早的版本是「全库清扫」——把 todo_tag 字典里
     * 所有无引用的条目全删。这在标签「按执行人隔离」的旧模型下没问题，但字典化之后
     * 标签是<b>全局共享词汇表</b>，管理员在字典管理里精心维护的标签并不因为「当前没任务
     * 在用」就变成垃圾。实测后果：刚 seed 的 12 个常用标签，只要删掉任意一条待办就被全扫空。
     *
     * <p>现在的语义（对应前端提示「删除最后一个使用某标签的任务时该标签也会被清理」）：
     * 删除任务时先记下该任务用过的标签 id，删完再逐个看是否已无引用 —— 只有这几个会被回收，
     * 其它无人使用的标签原样保留。
     *
     * @param candidateIds 该任务用过的 dict_item_id
     * @return 实际删除条数
     */
    @Transactional
    public int deleteOrphans(Collection<Long> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) return 0;
        Long dictId = tagDictId();
        Set<Long> referenced = new LinkedHashSet<>(taskTagRepo.findAllReferencedDictItemIds());
        List<SysDictItem> orphans = itemRepo.findAllById(new LinkedHashSet<>(candidateIds)).stream()
                // 双保险：即便调用方传错 id，也绝不误删 todo_tag 之外的字典条目
                .filter(i -> dictId.equals(i.getDictId()))
                .filter(i -> !referenced.contains(i.getId()))
                .toList();
        if (!orphans.isEmpty()) {
            itemRepo.deleteAll(orphans);
            log.info("[todo] orphan tag cleanup: candidates={} removed={} labels={}",
                    candidateIds.size(), orphans.size(),
                    orphans.stream().map(SysDictItem::getLabel).toList());
        }
        return orphans.size();
    }

    // ================= 内部 =================

    /**
     * 取 todo_tag 字典类型 id。
     * 类型被误删时直接 404 —— 不能像「返回空列表」那样静默降级，
     * 否则前端会看到一个空下拉而没有任何报错，标签功能看起来只是「没有标签」而已。
     */
    private Long tagDictId() {
        SysDictType t = typeRepo.findByCode(TAG_DICT_CODE)
                .orElseThrow(() -> new BizException(404, "任务标签字典类型缺失: " + TAG_DICT_CODE));
        return t.getId();
    }

    private static String normalize(String raw) {
        if (raw == null) throw new BizException(400, "标签不能为空");
        String s = raw.trim();
        if (s.isEmpty()) throw new BizException(400, "标签不能为空");
        if (s.length() > MAX_NAME_LEN) {
            throw new BizException(400, "标签长度不能超过 " + MAX_NAME_LEN + " 个字符");
        }
        return s;
    }
}
