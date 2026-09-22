package com.anomalyco.fapiao.web.security;

import com.anomalyco.fapiao.web.entity.SysPermission;
import com.anomalyco.fapiao.web.entity.SysRole;
import com.anomalyco.fapiao.web.entity.SysUser;
import com.anomalyco.fapiao.web.entity.SysUserRole;
import com.anomalyco.fapiao.web.repository.SysPermissionRepository;
import com.anomalyco.fapiao.web.repository.SysRolePermRepository;
import com.anomalyco.fapiao.web.repository.SysUserRepository;
import com.anomalyco.fapiao.web.repository.SysUserRoleRepository;
import com.anomalyco.fapiao.web.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final SysUserRepository userRepo;
    private final SysUserRoleRepository userRoleRepo;
    private final SysRolePermRepository rolePermRepo;
    private final SysPermissionRepository permRepo;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        SysUser user = userRepo.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(403, "User is disabled");
        }
        List<Long> roleIds = userRoleRepo.findByUserId(user.getId()).stream()
                .map(SysUserRole::getRoleId).toList();
        List<SysRole> roles = roleIds.isEmpty() ? List.of() : List.of();
        List<Long> permIds = roleIds.isEmpty() ? List.of() : rolePermRepo.findPermIdsByRoleIds(roleIds);
        List<SysPermission> perms = permIds.isEmpty() ? List.of() : permRepo.findByIdIn(permIds);
        return new AppUserDetails(user,
                roles.stream().map(SysRole::getCode).toList(),
                perms.stream().map(SysPermission::getCode).toList());
    }

    @Transactional(readOnly = true)
    public List<Long> rolesOf(Long userId) {
        return userRoleRepo.findByUserId(userId).stream()
                .map(SysUserRole::getRoleId)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> permsOf(List<Long> roleIds) {
        if (roleIds.isEmpty()) return List.of();
        List<SysPermission> perms = permRepo.findByRoleIds(roleIds);
        return perms.stream().map(SysPermission::getCode).toList();
    }
}