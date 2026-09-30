package com.anomalyco.ifinbrain.web.design.flow.service;

import com.anomalyco.ifinbrain.web.design.flow.dto.FlowHistoryDto;
import com.anomalyco.ifinbrain.web.design.flow.dto.FlowInstanceDto;
import com.anomalyco.ifinbrain.web.design.flow.entity.SysFlowDefine;
import com.anomalyco.ifinbrain.web.design.flow.entity.SysFlowHistory;
import com.anomalyco.ifinbrain.web.design.flow.entity.SysFlowInstance;
import com.anomalyco.ifinbrain.web.design.flow.repository.SysFlowDefineRepository;
import com.anomalyco.ifinbrain.web.design.flow.repository.SysFlowHistoryRepository;
import com.anomalyco.ifinbrain.web.design.flow.repository.SysFlowInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 流程实例查询（不含状态推进逻辑 —— 推进在 FlowEngineService）。
 */
@Service
@RequiredArgsConstructor
public class FlowInstanceService {

    private final SysFlowInstanceRepository repo;
    private final SysFlowDefineRepository defineRepo;
    private final SysFlowHistoryRepository historyRepo;

    @Transactional(readOnly = true)
    public List<FlowInstanceDto> list() {
        return repo.findAll().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public FlowInstanceDto detail(Long id) {
        SysFlowInstance inst = repo.findById(id).orElseThrow();
        FlowInstanceDto dto = toDto(inst);
        dto.setHistory(historyRepo.findAll().stream()
                .filter(h -> h.getInstanceId().equals(id))
                .map(this::toHistoryDto).toList());
        return dto;
    }

    private FlowInstanceDto toDto(SysFlowInstance inst) {
        SysFlowDefine def = defineRepo.findById(inst.getFlowId()).orElse(null);
        Map<String, Object> vars;
        try {
            vars = new tools.jackson.databind.ObjectMapper().readValue(
                    inst.getVariablesJson() == null ? "{}" : inst.getVariablesJson(),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) { vars = Map.of(); }

        // 任务计数（按 instanceId）
        int total = (int) repo.findAll().stream().filter(i -> i.getId().equals(inst.getId())).count();

        return FlowInstanceDto.builder()
                .id(inst.getId())
                .flowId(inst.getFlowId())
                .flowCode(inst.getFlowCode())
                .flowName(def != null ? def.getName() : null)
                .businessKey(inst.getBusinessKey())
                .state(inst.getState())
                .currentNodeId(inst.getCurrentNodeId())
                .variables(vars)
                .startedBy(inst.getStartedBy())
                .startedAt(inst.getStartedAt())
                .finishedAt(inst.getFinishedAt())
                .totalTaskCount(total)
                .build();
    }

    private FlowHistoryDto toHistoryDto(SysFlowHistory h) {
        return FlowHistoryDto.builder()
                .id(h.getId())
                .instanceId(h.getInstanceId())
                .nodeId(h.getNodeId())
                .nodeLabel(h.getNodeLabel())
                .action(h.getAction())
                .operator(h.getOperator())
                .operatorName(h.getOperatorName())
                .comment(h.getComment())
                .createdAt(h.getCreatedAt())
                .build();
    }
}