package com.anomalyco.ifinbrain.web.design.flow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FlowTaskDto {

    private Long id;
    private Long instanceId;
    private Long flowId;
    private String flowCode;
    private String flowName;
    private String businessKey;
    private String instanceState;
    private String nodeId;
    private String nodeLabel;
    /** USER / ROLE / ORG */
    private String assigneeType;
    private String assigneeId;
    private String assigneeName;
    /** PENDING / APPROVED / REJECTED / SKIPPED */
    private String status;
    private String comment;
    private Long todoTaskId;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private String startedBy;
    private java.time.LocalDateTime instanceStartedAt;
}