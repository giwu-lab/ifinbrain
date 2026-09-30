package com.anomalyco.ifinbrain.web.design.flow.enums;

/** 流程节点类型（与 mfish NodeType 对齐） */
public enum NodeType {
    /** 开始节点（唯一） */
    START,
    /** 结束节点（唯一） */
    END,
    /** 审批节点（userTask） */
    APPROVAL,
    /** 排他网关 / 条件分支 */
    CONDITION
}