package com.anomalyco.ifinbrain.web.dto.user;

import com.anomalyco.ifinbrain.web.entity.SysUser;
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
public class UserListItem {
    private Long id;
    private String username;
    private String displayName;
    private String email;
    private String phone;
    private Short status;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private List<Long> roleIds;
    private List<String> roleNames;

    public static UserListItem from(SysUser u, List<Long> roleIds, List<String> roleNames) {
        return UserListItem.builder()
                .id(u.getId())
                .username(u.getUsername())
                .displayName(u.getDisplayName())
                .email(u.getEmail())
                .phone(u.getPhone())
                .status(u.getStatus())
                .lastLoginAt(u.getLastLoginAt())
                .createdAt(u.getCreatedAt())
                .roleIds(roleIds)
                .roleNames(roleNames)
                .build();
    }
}