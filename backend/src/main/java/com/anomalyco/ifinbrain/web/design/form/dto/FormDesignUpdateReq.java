package com.anomalyco.ifinbrain.web.design.form.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class FormDesignUpdateReq {

    @Size(max = 64)
    @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}$",
             message = "code 必须是 kebab-case（[a-z0-9-]），首字符不能为 -")
    private String code;

    @Size(max = 128)
    private String name;

    @Size(max = 512)
    private String description;

    private List<FormFieldSchema> fields;

    private Short status;
}