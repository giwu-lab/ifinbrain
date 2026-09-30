package com.anomalyco.ifinbrain.web.design.flow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流程节点（Vue Flow 的 node）。
 * 字段结构与 mfish FlowNode 对齐：id + data
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FlowNode {
    private String id;
    private NodeData data;
}