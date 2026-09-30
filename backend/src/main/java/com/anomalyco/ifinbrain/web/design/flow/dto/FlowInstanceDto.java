package com.anomalyco.ifinbrain.web.design.flow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FlowInstanceDto {

    private Long id;
    private Long flowId;
    private String flowCode;
    private String flowName;
    private String businessKey;
    /** RUNNING / APPROVED / REJECTED / CANCELED */
    private String state;
    private String currentNodeId;
    private String currentNodeLabel;
    private java.util.Map<String, Object> variables;
    private String startedBy;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    /** 已完成的任务数（便捷展示） */
    private Integer completedTaskCount;
    /** 总任务数（含 PENDING + COMPLETED） */
    private Integer totalTaskCount;
    /** 历史记录 */
    private List<FlowHistoryDto> history;
}