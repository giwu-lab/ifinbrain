package com.anomalyco.fapiao.web.dto.role;

import com.anomalyco.fapiao.web.entity.SysRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleListItem {
    private Long id;
    private String code;
    private String name;
    private String remark;
    private Short status;
    private LocalDateTime createdAt;
    private List<Long> permissionIds;
    private List<String> permissionCodes;

    public static RoleListItem from(SysRole r, List<Long> permissionIds, List<String> permissionCodes) {
        return RoleListItem.builder()
                .id(r.getId())
                .code(r.getCode())
                .name(r.getName())
                .remark(r.getRemark())
                .status(r.getStatus())
                .createdAt(r.getCreatedAt())
                .permissionIds(permissionIds)
                .permissionCodes(permissionCodes)
                .build();
    }
}