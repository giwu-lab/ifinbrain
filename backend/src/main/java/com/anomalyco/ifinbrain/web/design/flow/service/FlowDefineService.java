package com.anomalyco.ifinbrain.web.design.flow.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.design.flow.dto.FlowDefineCreateReq;
import com.anomalyco.ifinbrain.web.design.flow.dto.FlowDefineDto;
import com.anomalyco.ifinbrain.web.design.flow.dto.FlowDefineUpdateReq;
import com.anomalyco.ifinbrain.web.design.flow.dto.FlowEdge;
import com.anomalyco.ifinbrain.web.design.flow.dto.FlowJson;
import com.anomalyco.ifinbrain.web.design.flow.dto.FlowNode;
import com.anomalyco.ifinbrain.web.design.flow.dto.NodeData;
import com.anomalyco.ifinbrain.web.design.flow.entity.SysFlowDefine;
import com.anomalyco.ifinbrain.web.design.flow.engine.ConditionEvaluator;
import com.anomalyco.ifinbrain.web.design.flow.enums.NodeType;
import com.anomalyco.ifinbrain.web.design.flow.repository.SysFlowDefineRepository;
import com.anomalyco.ifinbrain.web.entity.SysPermission;
import com.anomalyco.ifinbrain.web.entity.SysRole;
import com.anomalyco.ifinbrain.web.entity.SysRolePerm;
import com.anomalyco.ifinbrain.web.repository.SysPermissionRepository;
import com.anomalyco.ifinbrain.web.repository.SysRolePermRepository;
import com.anomalyco.ifinbrain.web.repository.SysRoleRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlowDefineService {

    /** 流程授权的 4 个 verb —— view/update/deploy/delete（创建/全局列表仍用 design:flow:*） */
    public static final List<String> FLOW_PERMS = List.of("view", "update", "deploy", "delete");
    private static final String ADMIN_CODE = "admin";
    /** 流程 perm code 模板：design:flow:{code}:{verb} */
    private static final String FLOW_PERM_CODE = "design:flow:%s:%s";
    /** 流程 perm code 前缀 */
    private static final String FLOW_PERM_PREFIX = "design:flow:";

    private static final Map<String, String> VERB_ZH = Map.of(
            "view", "查看",
            "update", "编辑",
            "deploy", "部署",
            "delete", "删除"
    );

    private final SysFlowDefineRepository repo;
    private final PermissionChecker perm;
    private final SysPermissionRepository permRepo;
    private final SysRolePermRepository rolePermRepo;
    private final SysRoleRepository roleRepo;
    private final ConditionEvaluator conditionEvaluator;

    // ===================== 查询 =====================

    @Transactional(readOnly = true)
    public List<FlowDefineDto> list() {
        perm.require("design:flow:view");
        return repo.findAllByOrderByIdAsc().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public FlowDefineDto detail(Long id) {
        perm.require("design:flow:view");
        SysFlowDefine e = repo.findById(id).orElseThrow(() -> new BizException(404, "流程不存在"));
        return toDto(e);
    }

    @Transactional(readOnly = true)
    public FlowDefineDto detailByCode(String code) {
        perm.require("design:flow:view");
        SysFlowDefine e = repo.findByCode(code).orElseThrow(() -> new BizException(404, "流程不存在: " + code));
        return toDto(e);
    }

    /**
     * 当前用户「可发起」流程列表：仅 status=1 的流程 + 当前用户对每个流程的 deploy perm。
     *
     * - admin 角色：返回所有已发布流程（admin 在所有 4 个 verb 上都有 perm）。
     * - 其他角色：返回当前用户角色有流程级 design:flow:{code}:deploy perm 的已发布流程。
     *
     * 注意：admin 角色的 4 verb 绑定由 ensureFlowPerms + seedAdminAccess 自动维护，
     * 所以 admin 无需特殊逻辑 —— 直接走下面的过滤也能命中。但为了清晰，仍显式短路。
     *
     * perm 兜底：design:flow:view（让用户至少能看到 list；具体能不能发起由前端按钮 + 后端 start perm 检查决定）。
     */
    @Transactional(readOnly = true)
    public List<FlowDefineDto> listLaunchableForUser(String username) {
        perm.require("design:flow:view");

        List<SysFlowDefine> allPublished = repo.findAllByOrderByIdAsc().stream()
                .filter(e -> e.getStatus() != null && e.getStatus() == 1)
                .toList();
        if (allPublished.isEmpty()) return List.of();

        // admin 短路：所有已发布都可见
        String roleCodes = perm.currentRoleCodes();
        boolean isAdmin = roleCodes != null && roleCodes.contains(ADMIN_CODE);
        if (isAdmin) {
            return allPublished.stream().map(this::toDto).toList();
        }

        // 非 admin：拉当前用户的角色 ids
        List<Long> myRoleIds = currentUserRoleIds();
        if (myRoleIds.isEmpty()) return List.of();

        // 当前用户角色绑定的所有 perm ids → 过滤出流程级 deploy perm code
        List<Long> myPermIds = rolePermRepo.findPermIdsByRoleIds(myRoleIds);
        if (myPermIds.isEmpty()) return List.of();
        List<SysPermission> myPerms = permRepo.findByIdIn(myPermIds);

        Set<String> myDeployCodes = myPerms.stream()
                .map(SysPermission::getCode)
                .filter(Objects::nonNull)
                .filter(c -> c.startsWith(FLOW_PERM_PREFIX) && c.endsWith(":deploy"))
                .collect(Collectors.toSet());

        return allPublished.stream()
                .filter(e -> myDeployCodes.contains(permCode(e.getCode(), "deploy")))
                .map(this::toDto)
                .toList();
    }

    /** 把当前用户的角色 codes 解析成 role ids；按 AuthPrincipal.roles 字符串 split。 */
    private List<Long> currentUserRoleIds() {
        String roleCodes = perm.currentRoleCodes();
        if (roleCodes == null || roleCodes.isBlank()) return List.of();
        List<Long> ids = new ArrayList<>();
        for (String code : roleCodes.split(",")) {
            String trimmed = code.trim();
            if (trimmed.isEmpty()) continue;
            roleRepo.findByCode(trimmed).ifPresent(r -> ids.add(r.getId()));
        }
        return ids;
    }

    private FlowDefineDto toDto(SysFlowDefine e) {
        FlowJson json = parseFlowJson(e.getFlowJson());
        int nodes = json == null || json.getNodes() == null ? 0 : json.getNodes().size();
        int edges = json == null || json.getEdges() == null ? 0 : json.getEdges().size();
        return FlowDefineDto.from(e, json, nodes, edges);
    }

    // ===================== 创建 =====================

    @Transactional
    public FlowDefineDto create(FlowDefineCreateReq req, String createdBy) {
        perm.require("design:flow:create");
        String code = req.getCode().trim();
        if (repo.existsByCode(code)) {
            throw new BizException(400, "流程 code 已存在: " + code);
        }
        validateFlowJson(req.getFlowJson());

        SysFlowDefine e = SysFlowDefine.builder()
                .code(code)
                .name(req.getName().trim())
                .description(req.getDescription())
                .flowJson(serializeFlowJson(req.getFlowJson()))
                .defaultFormCode(req.getDefaultFormCode())
                .status(req.getStatus() == null ? 0 : req.getStatus())
                .version(1)
                .createdBy(createdBy == null ? "system" : createdBy)
                .build();
        e = repo.save(e);
        // 注册 4 个 perm + 给 admin seed
        ensureFlowPerms(code, e.getName());
        seedAdminAccess(code);
        return toDto(e);
    }

    // ===================== 更新 =====================

    @Transactional
    public FlowDefineDto update(Long id, FlowDefineUpdateReq req) {
        perm.require("design:flow:update");
        SysFlowDefine e = repo.findById(id).orElseThrow(() -> new BizException(404, "流程不存在"));

        if (req.getCode() != null && !req.getCode().isBlank()) {
            String newCode = req.getCode().trim();
            if (!newCode.equals(e.getCode()) && repo.existsByCodeAndIdNot(newCode, id)) {
                throw new BizException(400, "流程 code 已存在: " + newCode);
            }
            e.setCode(newCode);
        }
        if (req.getName() != null && !req.getName().isBlank()) {
            e.setName(req.getName().trim());
        }
        if (req.getDescription() != null) e.setDescription(req.getDescription());
        if (req.getFlowJson() != null) {
            validateFlowJson(req.getFlowJson());
            e.setFlowJson(serializeFlowJson(req.getFlowJson()));
        }
        if (req.getDefaultFormCode() != null) e.setDefaultFormCode(req.getDefaultFormCode());
        if (req.getStatus() != null) e.setStatus(req.getStatus());

        e = repo.save(e);
        // 幂等保证 4 个 perm 已注册 + admin 已 seed（兼容历史 sample 数据未走 service.create 的情况）
        ensureFlowPerms(e.getCode(), e.getName());
        seedAdminAccess(e.getCode());
        return toDto(e);
    }

    // ===================== 删除 =====================

    @Transactional
    public void delete(Long id) {
        perm.require("design:flow:delete");
        SysFlowDefine e = repo.findById(id).orElseThrow(() -> new BizException(404, "流程不存在"));
        repo.delete(e);
        // 清理 perm code + 绑定
        unbindAndDeleteFlowPerms(e.getCode());
    }

    // ===================== 发布 / 反发布 =====================

    @Transactional
    public FlowDefineDto publish(Long id) {
        perm.require("design:flow:deploy");
        SysFlowDefine e = repo.findById(id).orElseThrow(() -> new BizException(404, "流程不存在"));
        validateFlowJson(parseFlowJson(e.getFlowJson()));
        e.setStatus((short) 1);
        e.setVersion(e.getVersion() + 1);
        e = repo.save(e);
        return toDto(e);
    }

    @Transactional
    public FlowDefineDto unpublish(Long id) {
        perm.require("design:flow:deploy");
        SysFlowDefine e = repo.findById(id).orElseThrow(() -> new BizException(404, "流程不存在"));
        e.setStatus((short) 0);
        e = repo.save(e);
        return toDto(e);
    }

    // ===================== 工具 =====================

    /**
     * 校验 FlowJson：节点 id 唯一 / 节点 type 合法 / 边 source、target 都存在 /
     * 仅 1 个 START、1 个 END / 每条边的条件表达式可被 SpEL 解析。
     *
     * <p>条件校验是必须的：引擎求值失败时只 log.warn 并按 false 处理（避免流程卡死），
     * 于是写错的表达式能一路保存成功，只在运行到该分支时静默走错路。放到保存这一步
     * 拦下来，用户至少能立刻看到是哪条线写错了。
     */
    private void validateFlowJson(FlowJson json) {
        if (json == null) return;
        List<FlowNode> nodes = json.getNodes();
        List<FlowEdge> edges = json.getEdges();

        Set<String> nodeIds = new HashSet<>();
        int startCount = 0;
        int endCount = 0;
        if (nodes != null) {
            for (FlowNode n : nodes) {
                if (n.getId() == null || n.getId().isBlank()) {
                    throw new BizException(400, "节点 id 不能为空");
                }
                if (!nodeIds.add(n.getId())) {
                    throw new BizException(400, "节点 id 重复: " + n.getId());
                }
                NodeData d = n.getData();
                if (d == null || d.getType() == null) {
                    throw new BizException(400, "节点 type 不能为空: node=" + n.getId());
                }
                if (d.getType() == NodeType.START) startCount++;
                else if (d.getType() == NodeType.END) endCount++;
                if (d.getType() == NodeType.APPROVAL) {
                    boolean hasUser = d.getUserIds() != null && !d.getUserIds().isEmpty();
                    boolean hasRole = d.getRoleIds() != null && !d.getRoleIds().isEmpty();
                    boolean hasOrg  = d.getOrgIds()  != null && !d.getOrgIds().isEmpty();
                    if (!hasUser && !hasRole && !hasOrg) {
                        throw new BizException(400, "审批节点必须配置至少一个审批人/角色/组织: " + n.getId());
                    }
                }
            }
            if (startCount != 1) throw new BizException(400, "流程必须有且仅有 1 个开始节点");
            if (endCount != 1)   throw new BizException(400, "流程必须有且仅有 1 个结束节点");
        }
        if (edges != null) {
            for (FlowEdge e : edges) {
                if (!nodeIds.contains(e.getSource())) {
                    throw new BizException(400, "连线 source 节点不存在: " + e.getSource());
                }
                if (!nodeIds.contains(e.getTarget())) {
                    throw new BizException(400, "连线 target 节点不存在: " + e.getTarget());
                }
                String cond = e.getData() == null ? null : e.getData().getCondition();
                if (cond != null && !cond.isBlank()) {
                    try {
                        conditionEvaluator.validate(cond);
                    } catch (BizException ex) {
                        throw new BizException(400,
                                "连线 " + e.getId() + "（" + e.getSource() + " → " + e.getTarget()
                                        + "）的条件不合法: " + ex.getMessage());
                    }
                }
            }
        }
    }

    private FlowJson parseFlowJson(String json) {
        if (json == null || json.isBlank()) return new FlowJson(List.of(), List.of());
        try {
            return new tools.jackson.databind.ObjectMapper().readValue(json, FlowJson.class);
        } catch (Exception e) {
            log.warn("parseFlowJson failed: {}", e.getMessage());
            return new FlowJson(List.of(), List.of());
        }
    }

    private String serializeFlowJson(FlowJson json) {
        if (json == null) return "{\"nodes\":[],\"edges\":[]}";
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(json);
        } catch (Exception e) {
            throw new BizException(500, "flowJson 序列化失败: " + e.getMessage());
        }
    }

    // ===================== 授权 =====================

    /** 拼 perm code：design:flow:{code}:{verb} */
    private static String permCode(String code, String verb) {
        return String.format(FLOW_PERM_CODE, code, verb);
    }

    /** perm code 前缀：design:flow:{code}: */
    private static String permPrefix(String code) {
        return FLOW_PERM_PREFIX + code + ":";
    }

    /** 注册流程的 4 个 perm（已存在则跳过；幂等） */
    private void ensureFlowPerms(String code, String flowName) {
        for (String verb : FLOW_PERMS) {
            String pcode = permCode(code, verb);
            if (!permRepo.existsByCode(pcode)) {
                permRepo.save(SysPermission.builder()
                        .code(pcode)
                        .name(flowName + " 流程" + VERB_ZH.get(verb))
                        .type("API")
                        .build());
            }
        }
    }

    /** 给 admin 角色 seed 4 个 perm —— operator/viewer 不 seed */
    private void seedAdminAccess(String code) {
        SysRole admin = roleRepo.findByCode(ADMIN_CODE)
                .orElseThrow(() -> new BizException(500, "admin 角色缺失"));
        for (String verb : FLOW_PERMS) {
            SysPermission p = permRepo.findByCode(permCode(code, verb))
                    .orElseThrow(() -> new BizException(500, "perm 缺失: " + permCode(code, verb)));
            if (!rolePermRepo.existsByRoleIdAndPermId(admin.getId(), p.getId())) {
                rolePermRepo.save(SysRolePerm.builder()
                        .roleId(admin.getId())
                        .permId(p.getId())
                        .build());
            }
        }
    }

    /**
     * code 改名：旧 code 的所有 sys_role_perm 绑定删除 + 删 sys_permission 行；新 code 已注册 + admin 重新 seed
     */
    private void migratePermsForCodeRename(String oldCode, String newCode, String newName) {
        unbindAndDeleteFlowPerms(oldCode);
        ensureFlowPerms(newCode, newName);
        seedAdminAccess(newCode);
        log.info("[flow] migrated perms: code {} -> {}", oldCode, newCode);
    }

    /**
     * 流程删除 / code 改名时：旧 perm code 的所有角色绑定删除 + 删 sys_permission 行本身。
     * 不能保留 perm 行 —— perm.name 字段含被删流程名，留着会污染角色列表 UI。
     */
    private void unbindAndDeleteFlowPerms(String code) {
        List<SysPermission> perms = permRepo.findByCodeStartingWith(permPrefix(code));
        for (SysPermission p : perms) {
            rolePermRepo.deleteByPermId(p.getId());
            permRepo.delete(p);
        }
        rolePermRepo.flush();
        permRepo.flush();
        log.info("[flow] unbound + deleted {} perms for deleted code={}", perms.size(), code);
    }

    /**
     * 查询流程 4 个 perm 当前绑定到哪些角色。
     * 返回 {view: [roleIds], update: [roleIds], deploy: [roleIds], delete: [roleIds]}
     */
    @Transactional(readOnly = true)
    public Map<String, List<Long>> getPermissions(Long id) {
        perm.require("design:flow:deploy");
        SysFlowDefine e = repo.findById(id)
                .orElseThrow(() -> new BizException(404, "流程不存在"));

        Map<String, List<Long>> result = new LinkedHashMap<>();
        for (String verb : FLOW_PERMS) {
            String code = permCode(e.getCode(), verb);
            Optional<SysPermission> p = permRepo.findByCode(code);
            if (p.isEmpty()) {
                result.put(verb, List.of());
                continue;
            }
            List<Long> roleIds = rolePermRepo.findAll().stream()
                    .filter(rp -> rp.getPermId().equals(p.get().getId()))
                    .map(SysRolePerm::getRoleId)
                    .toList();
            result.put(verb, roleIds);
        }
        return result;
    }

    /**
     * 全量替换流程 4 个 perm 的角色绑定。
     * 输入 {view: [roleIds], update: [roleIds], ...} —— 空列表 = 收回所有授权（除 admin）。
     * 注意：admin 角色的 4 个绑定永远保留（避免 admin 自我锁死）。
     */
    @Transactional
    public void updatePermissions(Long id, Map<String, List<Long>> grants) {
        perm.require("design:flow:deploy");
        SysFlowDefine e = repo.findById(id)
                .orElseThrow(() -> new BizException(404, "流程不存在"));

        Long adminId = roleRepo.findByCode(ADMIN_CODE)
                .orElseThrow(() -> new BizException(500, "admin 角色缺失"))
                .getId();

        for (String verb : FLOW_PERMS) {
            String code = permCode(e.getCode(), verb);
            SysPermission p = permRepo.findByCode(code)
                    .orElseThrow(() -> new BizException(500, "perm 缺失: " + code));

            List<Long> requested = grants == null ? List.of()
                    : grants.getOrDefault(verb, List.of());
            // admin 强制保留（即使入参里没勾）
            List<Long> finalRoleIds = new ArrayList<>(requested);
            if (!finalRoleIds.contains(adminId)) finalRoleIds.add(adminId);

            List<Long> currentRoleIds = rolePermRepo.findAll().stream()
                    .filter(rp -> rp.getPermId().equals(p.getId()))
                    .map(SysRolePerm::getRoleId)
                    .toList();

            List<Long> toAdd = finalRoleIds.stream().filter(r -> !currentRoleIds.contains(r)).toList();
            List<Long> toRemove = currentRoleIds.stream().filter(r -> !finalRoleIds.contains(r)).toList();

            for (Long rid : toAdd) {
                rolePermRepo.save(SysRolePerm.builder().roleId(rid).permId(p.getId()).build());
            }
            for (Long rid : toRemove) {
                rolePermRepo.deleteByRoleIdAndPermId(rid, p.getId());
            }
        }
        rolePermRepo.flush();
        log.info("[flow] updated permissions for flow id={} code={}", id, e.getCode());
    }
}