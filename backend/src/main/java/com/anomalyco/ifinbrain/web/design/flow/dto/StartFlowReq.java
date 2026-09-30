package com.anomalyco.ifinbrain.web.design.flow.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class StartFlowReq {

    @NotBlank
    private String code;             // 流程 code

    @NotBlank
    private String businessKey;       // 业务表主键

    /** 流程变量（如表单数据），可空 */
    private Map<String, Object> variables;

    /** 发起人 override（admin 可代发；普通用户 = 当前登录用户） */
    private String startBy;
}