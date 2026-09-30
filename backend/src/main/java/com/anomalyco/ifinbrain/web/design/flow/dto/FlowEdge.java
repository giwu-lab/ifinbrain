package com.anomalyco.ifinbrain.web.design.flow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流程连线（Vue Flow 的 edge）。
 * 字段结构与 mfish FlowEdge 对齐。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FlowEdge {
    private String id;
    private String source;
    private String target;
    private EdgeData data;
}