package com.anomalyco.ifinbrain.web.design.flow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 节点在画布上的坐标（Vue Flow 的 node.position）。
 *
 * <p>必须落库：FlowNode 早先只有 id + data，Jackson 反序列化时会把前端传来的
 * {@code position} 静默丢弃（未知字段不报错），于是用户在设计器里拖动节点、保存、
 * 重新打开，坐标全部回到默认值 —— 多个节点叠在同一个点。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NodePosition {

    private Double x;
    private Double y;
}
