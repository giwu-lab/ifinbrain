package com.anomalyco.ifinbrain.web.design.flow.enums;

/** 审批模式（与 mfish ApproveType 对齐） */
public enum ApproveType {
    /** 或签：任一候选人通过即完成 */
    OR,
    /** 会签：所有候选人都通过才算完成 */
    AND,
    /** 按比例完成：达到 completionPercent 比例即完成 */
    PERCENT
}