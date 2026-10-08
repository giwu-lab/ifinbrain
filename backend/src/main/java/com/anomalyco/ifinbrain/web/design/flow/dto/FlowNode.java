package com.anomalyco.ifinbrain.web.design.flow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流程节点（Vue Flow 的 node）。
 * 字段结构与 mfish FlowNode 对齐：id + data。
 *
 * <p>type / position 必须保留：前端 Vue Flow 的节点带这两个字段，后端 DTO 少了它们时
 * Jackson 会静默丢弃（未知字段不报错），表现为「节点坐标保存后丢失、重新打开全部重叠」。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FlowNode {
    private String id;

    /** Vue Flow 节点类型：'custom' / 'input' / 'output'（可空） */
    private String type;

    /** 画布坐标（可空；为 null 时前端会自动布局） */
    private NodePosition position;

    private NodeData data;
}