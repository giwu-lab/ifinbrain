package com.anomalyco.ifinbrain.web.design.flow.engine;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.design.flow.dto.*;
import com.anomalyco.ifinbrain.web.design.flow.enums.NodeType;
import com.anomalyco.ifinbrain.web.design.flow.entity.SysFlowDefine;
import com.anomalyco.ifinbrain.web.design.flow.entity.SysFlowHistory;
import com.anomalyco.ifinbrain.web.design.flow.entity.SysFlowInstance;
import com.anomalyco.ifinbrain.web.design.flow.entity.SysFlowTask;
import com.anomalyco.ifinbrain.web.design.flow.repository.*;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 流程引擎（自研状态机 + 适配 BPMN 概念）。
 *
 * <p>说明：原计划用 Flowable 引擎，但与 Spring Boot 4 的多数据源兼容性问题无法解决
 * （始终 init 主 DataSource）。改为自研：数据存 sys_flow_instance / task / history
 * （已建），状态推进逻辑直接实现。后续 Flowable 出兼容版本时可平滑切换（schema 已对齐）。
 *
 * <p>API：
 * <ul>
 *     <li>{@link #deploy(Long)} —— 校验 / 升级版本号</li>
 *     <li>{@link #start(StartFlowReq, String)} —— 启动实例</li>
 *     <li>{@link #approve(Long, ApproveTaskReq, String)} —— 审批通过</li>
 *     <li>{@link #reject(Long, ApproveTaskReq, String)} —— 审批拒绝</li>
 *     <li>{@link #pendingTasks(String)} / {@link #allTasks(String)} —— 当前用户的任务</li>
 * </ul>
 *
 * <p>todo 联动：本期保留字段 sys_flow_task.todo_task_id，但 TodoTaskService 未实现
 * createFromFlow / markStatusFromFlow，todoIntegration 暂时是 no-op。Phase 4.x 再补。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowEngineService {

    private final SysFlowDefineRepository defineRepo;
    private final SysFlowInstanceRepository instanceRepo;
    private final SysFlowTaskRepository taskRepo;
    private final SysFlowHistoryRepository historyRepo;
    private final ConditionEvaluator evaluator;
    private final CallbackInvoker callbackInvoker;
    private final PermissionChecker perm;
    private final ObjectMapper jackson = new ObjectMapper();

    // ===================== Deploy =====================

    @Transactional
    public SysFlowDefine deploy(Long flowId) {
        perm.require("design:flow:deploy");
        SysFlowDefine def = defineRepo.findById(flowId)
                .orElseThrow(() -> new BizException(404, "流程不存在"));
        if (def.getStatus() == null || def.getStatus() == 0) {
            throw new BizException(400, "流程未发布，无法 deploy");
        }
        def.setVersion(def.getVersion() + 1);
        return defineRepo.save(def);
    }

    // ===================== Start =====================

    @Transactional
    public SysFlowInstance start(StartFlowReq req, String defaultStartBy) {
        perm.require("design:flow:deploy");
        if (req.getCode() == null) throw new BizException(400, "code 必填");
        SysFlowDefine def = defineRepo.findByCode(req.getCode())
                .orElseThrow(() -> new BizException(404, "流程不存在: " + req.getCode()));
        if (def.getStatus() == null || def.getStatus() == 0) {
            throw new BizException(400, "流程未发布，无法启动");
        }
        boolean exists = instanceRepo.findAll().stream().anyMatch(i ->
                def.getId().equals(i.getFlowId()) && req.getBusinessKey().equals(i.getBusinessKey()));
        if (exists) throw new BizException(400, "流程实例已存在（同一 code + businessKey）");

        FlowJson json = parse(def.getFlowJson());
        if (json.getNodes() == null || json.getNodes().isEmpty()) {
            throw new BizException(400, "流程定义为空");
        }
        FlowNode startNode = json.getNodes().stream()
                .filter(n -> n.getData() != null && n.getData().getType() == NodeType.START)
                .findFirst().orElseThrow(() -> new BizException(400, "缺少开始节点"));

        String startedBy = (req.getStartBy() != null && !req.getStartBy().isBlank())
                ? req.getStartBy() : defaultStartBy;
        SysFlowInstance inst = SysFlowInstance.builder()
                .flowId(def.getId())
                .flowCode(def.getCode())
                .businessKey(req.getBusinessKey())
                .state("RUNNING")
                .currentNodeId(startNode.getId())
                .variablesJson(serializeVariables(req.getVariables()))
                .startedBy(startedBy)
                .startedAt(LocalDateTime.now())
                .build();
        inst = instanceRepo.save(inst);

        appendHistory(inst.getId(), startNode.getId(), startNode.getData().getLabel(), "SUBMIT", startedBy, null);

        advance(inst, json, startNode, "approved", req.getVariables(), startedBy);

        return instanceRepo.findById(inst.getId()).orElseThrow();
    }

    // ===================== Approve / Reject =====================

    @Transactional
    public SysFlowTask approve(Long taskId, ApproveTaskReq req, String operator) {
        perm.require("design:flow:deploy");
        SysFlowTask task = taskRepo.findById(taskId).orElseThrow(() -> new BizException(404, "任务不存在"));
        if (!"PENDING".equals(task.getStatus())) throw new BizException(400, "任务已处理");
        SysFlowInstance inst = instanceRepo.findById(task.getInstanceId()).orElseThrow(() -> new BizException(400, "实例不存在"));
        if (!"RUNNING".equals(inst.getState())) throw new BizException(400, "实例已结束");

        SysFlowDefine def = defineRepo.findById(inst.getFlowId()).orElseThrow(() -> new BizException(400, "流程已删除"));
        FlowJson json = parse(def.getFlowJson());

        task.setStatus("APPROVED");
        task.setComment(req.getComment());
        task.setCompletedAt(LocalDateTime.now());
        taskRepo.save(task);

        FlowNode curNode = findNode(json, task.getNodeId());
        appendHistory(inst.getId(), task.getNodeId(),
                curNode != null ? curNode.getData().getLabel() : task.getNodeLabel(),
                "APPROVE", operator, req.getComment());

        Map<String, Object> vars = parseVariables(inst.getVariablesJson());
        advance(inst, json, curNode, "approved", vars, operator);

        return taskRepo.findById(taskId).orElseThrow();
    }

    @Transactional
    public SysFlowTask reject(Long taskId, ApproveTaskReq req, String operator) {
        perm.require("design:flow:deploy");
        SysFlowTask task = taskRepo.findById(taskId).orElseThrow(() -> new BizException(404, "任务不存在"));
        if (!"PENDING".equals(task.getStatus())) throw new BizException(400, "任务已处理");
        SysFlowInstance inst = instanceRepo.findById(task.getInstanceId()).orElseThrow(() -> new BizException(400, "实例不存在"));
        SysFlowDefine def = defineRepo.findById(inst.getFlowId()).orElseThrow(() -> new BizException(400, "流程已删除"));
        FlowJson json = parse(def.getFlowJson());

        task.setStatus("REJECTED");
        task.setComment(req.getComment());
        task.setCompletedAt(LocalDateTime.now());
        taskRepo.save(task);

        FlowNode curNode = findNode(json, task.getNodeId());
        appendHistory(inst.getId(), task.getNodeId(),
                curNode != null ? curNode.getData().getLabel() : task.getNodeLabel(),
                "REJECT", operator, req.getComment());

        inst.setState("REJECTED");
        inst.setCurrentNodeId(task.getNodeId());
        inst.setFinishedAt(LocalDateTime.now());
        instanceRepo.save(inst);

        return taskRepo.findById(taskId).orElseThrow();
    }

    // ===================== Query =====================

    @Transactional(readOnly = true)
    public List<SysFlowTask> pendingTasks(String username) {
        return taskRepo.findAll().stream()
                .filter(t -> "PENDING".equals(t.getStatus()))
                .filter(t -> "USER".equals(t.getAssigneeType()) && username.equals(t.getAssigneeId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SysFlowTask> allTasks(String username) {
        return taskRepo.findAll().stream()
                .filter(t -> username.equals(t.getAssigneeId()))
                .toList();
    }

    // ===================== State machine =====================

    private void advance(SysFlowInstance inst, FlowJson json, FlowNode cur, String result,
                         Map<String, Object> vars, String operator) {
        if (cur == null || cur.getData() == null) return;
        List<FlowEdge> outs = json.getEdges() == null ? List.of() :
                json.getEdges().stream().filter(e -> cur.getId().equals(e.getSource())).toList();

        FlowNode next = null;
        for (FlowEdge e : outs) {
            String cond = e.getData() == null ? null : e.getData().getCondition();
            if (evaluator.evaluate(cond, vars, result)) {
                next = findNode(json, e.getTarget());
                break;
            }
        }
        if (next == null) {
            inst.setState("APPROVED");
            inst.setFinishedAt(LocalDateTime.now());
            instanceRepo.save(inst);
            log.info("[flow] instance {} finished APPROVED (no outgoing edge matched)", inst.getId());
            return;
        }

        NodeType nt2 = next.getData().getType();
        if (nt2 == NodeType.END) {
            inst.setCurrentNodeId(next.getId());
            inst.setState("APPROVED");
            inst.setFinishedAt(LocalDateTime.now());
            instanceRepo.save(inst);
            appendHistory(inst.getId(), next.getId(), next.getData().getLabel(), "COMPLETE", operator, null);
        } else if (nt2 == NodeType.APPROVAL) {
            inst.setCurrentNodeId(next.getId());
            instanceRepo.save(inst);
            createTaskForApproval(inst, next);
        } else if (nt2 == NodeType.CONDITION) {
            inst.setCurrentNodeId(next.getId());
            instanceRepo.save(inst);
            advance(inst, json, next, result, vars, operator);
        }
    }

    private void createTaskForApproval(SysFlowInstance inst, FlowNode node) {
        NodeData d = node.getData();
        String assigneeType = "USER";
        String assigneeId = null;
        String assigneeName = null;
        if (d.getUserIds() != null && !d.getUserIds().isEmpty()) {
            assigneeId = d.getUserIds().get(0);
            assigneeName = assigneeId;
        } else if (d.getRoleIds() != null && !d.getRoleIds().isEmpty()) {
            assigneeType = "ROLE";
            assigneeId = d.getRoleIds().get(0);
            assigneeName = assigneeId;
        } else if (d.getOrgIds() != null && !d.getOrgIds().isEmpty()) {
            assigneeType = "ORG";
            assigneeId = d.getOrgIds().get(0);
            assigneeName = assigneeId;
        } else {
            throw new BizException(400, "审批节点「" + d.getLabel() + "」未配置审批人/角色/组织");
        }

        SysFlowTask task = SysFlowTask.builder()
                .instanceId(inst.getId())
                .flowId(inst.getFlowId())
                .nodeId(node.getId())
                .nodeLabel(d.getLabel())
                .assigneeType(assigneeType)
                .assigneeId(assigneeId)
                .assigneeName(assigneeName)
                .status("PENDING")
                .createdAt(LocalDateTime.now())
                .build();
        taskRepo.save(task);
        // todo 联动：no-op（TodoTaskService 未提供 createFromFlow）
    }

    private void appendHistory(Long instanceId, String nodeId, String nodeLabel,
                              String action, String operator, String comment) {
        SysFlowHistory h = SysFlowHistory.builder()
                .instanceId(instanceId)
                .nodeId(nodeId == null ? "" : nodeId)
                .nodeLabel(nodeLabel)
                .action(action)
                .operator(operator == null ? "system" : operator)
                .operatorName(operator)
                .comment(comment)
                .createdAt(LocalDateTime.now())
                .build();
        historyRepo.save(h);
    }

    // ===================== Tools =====================

    private FlowNode findNode(FlowJson j, String id) {
        if (j.getNodes() == null || id == null) return null;
        return j.getNodes().stream().filter(n -> id.equals(n.getId())).findFirst().orElse(null);
    }

    private String serializeVariables(Map<String, Object> v) {
        if (v == null) return "{}";
        try { return jackson.writeValueAsString(v); }
        catch (Exception e) { return "{}"; }
    }

    private Map<String, Object> parseVariables(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return jackson.readValue(json, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { return Map.of(); }
    }

    private FlowJson parse(String json) {
        if (json == null || json.isBlank()) return new FlowJson();
        try { return jackson.readValue(json, FlowJson.class); }
        catch (Exception e) { return new FlowJson(); }
    }
}