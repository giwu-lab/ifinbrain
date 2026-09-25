package com.anomalyco.ifinbrain.web.dto.role;

import lombok.Data;

import java.util.List;

@Data
public class RoleUpdateReq {
    private String name;
    private String remark;
    private Short status;
    private List<Long> permissionIds;
}