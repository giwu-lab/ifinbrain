package com.anomalyco.ifinbrain.web.design.flow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 流程 JSON 根对象。
 * 与 mfish FlowJson 100% 兼容。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FlowJson {
    private List<FlowNode> nodes;
    private List<FlowEdge> edges;
}