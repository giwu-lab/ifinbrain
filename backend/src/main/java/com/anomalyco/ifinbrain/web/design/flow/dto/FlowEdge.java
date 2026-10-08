package com.anomalyco.ifinbrain.web.design.flow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流程连线（Vue Flow 的 edge）。
 * 字段结构与 mfish FlowEdge 对齐。
 *
 * <p>type 需保留：前端 FlowEdge 带可选 type（'smoothstep' / 'default'），
 * DTO 少了它会被 Jackson 静默丢弃，保存后连线样式退回默认。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FlowEdge {
    private String id;
    private String source;
    private String target;
    /** Vue Flow 连线类型，可空 */
    private String type;
    private EdgeData data;
}