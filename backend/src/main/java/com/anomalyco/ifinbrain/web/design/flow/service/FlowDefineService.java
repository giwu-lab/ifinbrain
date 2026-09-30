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
import com.anomalyco.ifinbrain.web.design.flow.enums.NodeType;
import com.anomalyco.ifinbrain.web.design.flow.repository.SysFlowDefineRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlowDefineService {

    private final SysFlowDefineRepository repo;
    private final PermissionChecker perm;

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
        return toDto(e);
    }

    // ===================== 删除 =====================

    @Transactional
    public void delete(Long id) {
        perm.require("design:flow:delete");
        SysFlowDefine e = repo.findById(id).orElseThrow(() -> new BizException(404, "流程不存在"));
        repo.delete(e);
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

    /** 校验 FlowJson：节点 id 唯一 / 节点 type 合法 / 边 source/target 都存在 / 仅 1 个 START、1 个 END */
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
}