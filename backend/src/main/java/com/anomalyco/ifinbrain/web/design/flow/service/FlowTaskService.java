package com.anomalyco.ifinbrain.web.design.flow.service;

import com.anomalyco.ifinbrain.web.design.flow.dto.FlowTaskDto;
import com.anomalyco.ifinbrain.web.design.flow.entity.SysFlowDefine;
import com.anomalyco.ifinbrain.web.design.flow.entity.SysFlowInstance;
import com.anomalyco.ifinbrain.web.design.flow.entity.SysFlowTask;
import com.anomalyco.ifinbrain.web.design.flow.repository.SysFlowDefineRepository;
import com.anomalyco.ifinbrain.web.design.flow.repository.SysFlowInstanceRepository;
import com.anomalyco.ifinbrain.web.design.flow.repository.SysFlowTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 流程任务查询（不含审批推进 —— 推进在 FlowEngineService）。
 */
@Service
@RequiredArgsConstructor
public class FlowTaskService {

    private final SysFlowTaskRepository taskRepo;
    private final SysFlowInstanceRepository instanceRepo;
    private final SysFlowDefineRepository defineRepo;

    @Transactional(readOnly = true)
    public FlowTaskDto detail(Long id) {
        SysFlowTask t = taskRepo.findById(id).orElseThrow();
        return toDto(t);
    }

    /**
     * 当前用户的待办任务数 —— dashboard 卡片用。
     * 过滤条件对齐 FlowEngineService.pendingTasks(username)：
     *   status='PENDING' + assigneeType='USER' + assigneeId=username
     */
    @Transactional(readOnly = true)
    public long pendingCount(String username) {
        return taskRepo.countByStatusAndAssigneeTypeAndAssigneeId("PENDING", "USER", username);
    }

    private FlowTaskDto toDto(SysFlowTask t) {
        SysFlowInstance inst = instanceRepo.findById(t.getInstanceId()).orElse(null);
        SysFlowDefine def = defineRepo.findById(t.getFlowId()).orElse(null);

        return FlowTaskDto.builder()
                .id(t.getId())
                .instanceId(t.getInstanceId())
                .flowId(t.getFlowId())
                .flowCode(def != null ? def.getCode() : null)
                .flowName(def != null ? def.getName() : null)
                .businessKey(inst != null ? inst.getBusinessKey() : null)
                .instanceState(inst != null ? inst.getState() : null)
                .nodeId(t.getNodeId())
                .nodeLabel(t.getNodeLabel())
                .assigneeType(t.getAssigneeType())
                .assigneeId(t.getAssigneeId())
                .assigneeName(t.getAssigneeName())
                .status(t.getStatus())
                .comment(t.getComment())
                .todoTaskId(t.getTodoTaskId())
                .createdAt(t.getCreatedAt())
                .completedAt(t.getCompletedAt())
                .startedBy(inst != null ? inst.getStartedBy() : null)
                .instanceStartedAt(inst != null ? inst.getStartedAt() : null)
                .build();
    }

    public Map<String, Object> mapOf(SysFlowTask t) {
        return toDto(t) == null ? Map.of() : java.util.stream.Stream.of(toDto(t)).findFirst().map(d -> (Map) d).orElse(Map.of());
    }
}