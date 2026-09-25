package com.anomalyco.ifinbrain.web.dto.role;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class RoleCreateReq {
    @NotBlank
    private String code;
    @NotBlank
    private String name;
    private String remark;
    private Short status;
    private List<Long> permissionIds;
}