package com.anomalyco.ifinbrain.web.design.flow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 连线业务数据（Vue Flow 的 edge.data） */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class EdgeData {
    /** 条件表达式：空=无条件；approved/rejected=系统常量；${...}=SpEL */
    private String condition;
}