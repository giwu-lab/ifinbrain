package com.anomalyco.ifinbrain.web.design.flow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class FlowDefineCreateReq {

    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}$",
             message = "code 必须是 kebab-case（[a-z0-9-]），首字符不能为 -")
    private String code;

    @NotBlank
    @Size(max = 128)
    private String name;

    @Size(max = 512)
    private String description;

    private FlowJson flowJson;

    @Size(max = 64)
    private String defaultFormCode;

    /** 可空，默认 0（草稿） */
    private Short status;
}