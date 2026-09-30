package com.anomalyco.ifinbrain.web.design.flow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FlowHistoryDto {

    private Long id;
    private Long instanceId;
    private String nodeId;
    private String nodeLabel;
    /** SUBMIT / APPROVE / REJECT / DELEGATE / JUMP */
    private String action;
    private String operator;
    private String operatorName;
    private String comment;
    private LocalDateTime createdAt;
}