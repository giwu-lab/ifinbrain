package com.anomalyco.ifinbrain.web.design.flow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 条件分支（CONDITION 节点的出边条件） */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Branch {

    /** 分支显示名（如「是」/「否」/「金额 > 10000」） */
    private String label;

    /**
     * 条件表达式：
     *   - "" / null  = 默认分支（最后一条）
     *   - "approved" / "rejected"  = 系统常量
     *   - "${amount > 10000}"  = SpEL 表达式（Phase 4 用 Spring ExpressionParser 解析）
     */
    private String condition;
}