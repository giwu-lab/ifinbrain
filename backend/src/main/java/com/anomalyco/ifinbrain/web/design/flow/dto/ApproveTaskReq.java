package com.anomalyco.ifinbrain.web.design.flow.dto;

import lombok.Data;

@Data
public class ApproveTaskReq {
    private String comment;
    /** 可选：false=只审核当前；true=会签模式下所有人必须通过才算完成（Phase 4 仅 OR，固定 true） */
    private Boolean completeAll;
}