package com.anomalyco.ifinbrain.web.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.dto.dept.DeptReqs;
import com.anomalyco.ifinbrain.web.entity.SysDept;
import com.anomalyco.ifinbrain.web.repository.SysDeptRepository;
import com.anomalyco.ifinbrain.web.repository.SysUserRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 组织架构（部门）。
 *
 * <p>树形 CRUD。与 {@link MenuService} 的差别：删除时**不级联**，有子部门或仍被用户引用
 * 一律拒绝 —— 部门被审批人选取、档案库、sys_user.dept_id 等引用，
 * 静默级联会把这些引用留成悬空 id。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysDeptService {

    private final SysDeptRepository repo;
    private final SysUserRepository userRepo;
    private final PermissionChecker perm;

    // ===================== 查询 =====================

    /**
     * 部门及其<b>所有后代</b>的 id 集合（含自身）。
     *
     * <p>供 todo 的「本部门任务可写、跨部门只读」判定使用（见 TodoTaskService.canWrite）。
     * 不要求任何权限 —— 它只回答"哪些 id 属于这棵子树"，越权与否由调用方自己判。
     *
     * <p>实现上按 parent_id 反复收敛：每轮把 parent 已在集合内的部门并进来，直到不再新增。
     * 部门表规模小（几十行），这样写不需要递归查询或 CTE，且对脏数据成环是安全的 ——
     * 成环时两个节点互相等待，谁也进不了集合，最终只是少算而不会死循环。
     */
    @Transactional(readOnly = true)
    public Set<Long> subtreeIds(Long deptId) {
        if (deptId == null) return Set.of();
        List<SysDept> all = repo.findAllByOrderByParentIdAscSortAscIdAsc();
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(deptId);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (SysDept d : all) {
                if (ids.contains(d.getParentId()) && ids.add(d.getId())) {
                    changed = true;
                }
            }
        }
        return ids;
    }

    /**
     * 部门树 —— 选择器参考数据，**只要求已登录，不要求 system:dept:list**。
     *
     * <p>表单 DEPT 字段（DeptField.vue）与流程审批节点的组织选取（ApprovalNodeConfig.vue）
     * 都在运行时调本端点，而填表人未必持有组织架构的管理权限；若在这里挂
     * system:dept:list，这些人在表单里就选不出部门（403）。
     *
     * <p>组织架构管理页本身不靠这里防护：菜单 27 带 permission=system:dept:list，
     * 动态路由只按用户自己的菜单构建，前置守卫再校验 meta.permission（见 router/index.ts）。
     * 写操作仍各自挂 create / update / delete。
     */
    @Transactional(readOnly = true)
    public List<DeptReqs.Node> tree() {
        return buildTree(repo.findAllByOrderByParentIdAscSortAscIdAsc());
    }

    /** 管理视角的扁平列表 —— 属组织架构管理数据，需要 system:dept:list */
    @Transactional(readOnly = true)
    public List<DeptReqs.Node> flatList() {
        perm.require("system:dept:list");
        return repo.findAllByOrderByParentIdAscSortAscIdAsc().stream().map(this::toNode).toList();
    }

    @Transactional(readOnly = true)
    public DeptReqs.Node detail(Long id) {
        perm.require("system:dept:list");
        return toNode(find(id));
    }

    // ===================== 写操作 =====================

    @Transactional
    public Long create(DeptReqs.Create req) {
        perm.require("system:dept:create");
        long parentId = normalizeParent(req.getParentId());
        requireParentExists(parentId);
        String code = trimToNull(req.getCode());
        if (code != null && repo.existsByCode(code)) {
            throw new BizException(400, "部门编码已存在: " + code);
        }
        SysDept e = SysDept.builder()
                .parentId(parentId)
                .name(req.getName().trim())
                .code(code)
                .leader(trimToNull(req.getLeader()))
                .phone(trimToNull(req.getPhone()))
                .sort(req.getSort() == null ? 0 : req.getSort())
                .status(req.getStatus() == null ? (short) 1 : req.getStatus())
                .remark(trimToNull(req.getRemark()))
                .build();
        return repo.save(e).getId();
    }

    @Transactional
    public void update(Long id, DeptReqs.Update req) {
        perm.require("system:dept:update");
        SysDept e = find(id);

        if (req.getParentId() != null) {
            long parentId = normalizeParent(req.getParentId());
            if (parentId != 0L) {
                requireParentExists(parentId);
                requireNoCycle(e.getId(), parentId);
            }
            e.setParentId(parentId);
        }
        if (req.getName() != null && !req.getName().isBlank()) {
            e.setName(req.getName().trim());
        }
        if (req.getCode() != null) {
            String code = trimToNull(req.getCode());
            if (code != null && repo.existsByCodeAndIdNot(code, id)) {
                throw new BizException(400, "部门编码已存在: " + code);
            }
            e.setCode(code);
        }
        if (req.getLeader() != null) e.setLeader(trimToNull(req.getLeader()));
        if (req.getPhone() != null) e.setPhone(trimToNull(req.getPhone()));
        if (req.getSort() != null) e.setSort(req.getSort());
        if (req.getStatus() != null) e.setStatus(req.getStatus());
        if (req.getRemark() != null) e.setRemark(trimToNull(req.getRemark()));

        repo.save(e);
    }

    @Transactional
    public void delete(Long id) {
        perm.require("system:dept:delete");
        SysDept e = find(id);
        long children = repo.findByParentId(id).size();
        if (children > 0) {
            throw new BizException(409, "该部门下还有 " + children + " 个子部门，请先删除或迁移后再删");
        }
        // sys_user.dept_id 没有外键（与本库其它关联表一致），所以删除前必须自己查引用，
        // 否则删完就留下一批查不出部门的用户，且 id 已被复用时更难排查。
        long users = userRepo.countByDeptId(e.getId());
        if (users > 0) {
            throw new BizException(409, "该部门下还有 " + users + " 名用户，请先调整归属后再删");
        }
        repo.deleteById(e.getId());
    }

    // ===================== 工具 =====================

    private SysDept find(Long id) {
        return repo.findById(id).orElseThrow(() -> new BizException(404, "部门不存在"));
    }

    private long normalizeParent(Long parentId) {
        return parentId == null ? 0L : parentId;
    }

    private void requireParentExists(long parentId) {
        if (parentId != 0L && !repo.existsById(parentId)) {
            throw new BizException(400, "上级部门不存在: " + parentId);
        }
    }

    /**
     * 拒绝把部门挂到它自己或它的后代下面 —— 否则树成环，buildTree 会无限递归。
     * 从候选父级一路向上走，若途中撞见自己即成环。
     */
    private void requireNoCycle(Long deptId, long newParentId) {
        if (deptId.equals(newParentId)) {
            throw new BizException(400, "上级部门不能是自己");
        }
        Set<Long> seen = new HashSet<>();
        Long cur = newParentId;
        while (cur != null && cur != 0L) {
            if (!seen.add(cur)) {
                // 已有脏数据成环，直接拦下，不继续往上走
                throw new BizException(400, "上级部门链路已存在环，无法调整");
            }
            if (deptId.equals(cur)) {
                throw new BizException(400, "不能把部门移动到它自己的下级");
            }
            cur = repo.findById(cur).map(SysDept::getParentId).orElse(null);
        }
    }

    private List<DeptReqs.Node> buildTree(List<SysDept> all) {
        Map<Long, DeptReqs.Node> map = new LinkedHashMap<>();
        for (SysDept d : all) {
            DeptReqs.Node n = toNode(d);
            n.setChildren(new ArrayList<>());
            map.put(d.getId(), n);
        }
        List<DeptReqs.Node> roots = new ArrayList<>();
        for (SysDept d : all) {
            DeptReqs.Node n = map.get(d.getId());
            Long pid = d.getParentId();
            if (pid == null || pid == 0L || !map.containsKey(pid)) {
                roots.add(n);
            } else {
                map.get(pid).getChildren().add(n);
            }
        }
        return roots;
    }

    private DeptReqs.Node toNode(SysDept d) {
        DeptReqs.Node n = new DeptReqs.Node();
        n.setId(d.getId());
        n.setParentId(d.getParentId());
        n.setName(d.getName());
        n.setCode(d.getCode());
        n.setLeader(d.getLeader());
        n.setPhone(d.getPhone());
        n.setSort(d.getSort());
        n.setStatus(d.getStatus());
        n.setRemark(d.getRemark());
        n.setCreatedAt(d.getCreatedAt() == null ? null : d.getCreatedAt().toString());
        n.setUpdatedAt(d.getUpdatedAt() == null ? null : d.getUpdatedAt().toString());
        n.setLabel(d.getName());
        n.setValue(d.getId());
        return n;
    }

    private String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
