package com.anomalyco.fapiao.web.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInfo {
    private Long id;
    private String username;
    private String displayName;
    private String email;
    private String phone;
    private List<String> roles;
    private List<String> permissions;
    private List<MenuNode> menus;
}