package com.anomalyco.ifinbrain.web.design.portal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class PortalPageCreateReq {

    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "^[a-z0-9][a-z0-9-]*$", message = "code 必须是 kebab-case（小写字母/数字/连字符）")
    private String code;

    @NotBlank
    @Size(max = 128)
    private String name;

    @Size(max = 512)
    private String description;

    /** JSON 字符串：layout_schema */
    private String layoutSchema;

    /** 角色绑定；为空表示不绑定任何角色 */
    private List<String> roleBindings;

    /** 可选：默认 0（draft） */
    private Short status;
}