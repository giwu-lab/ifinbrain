package com.anomalyco.ifinbrain.web.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.common.PageReq;
import com.anomalyco.ifinbrain.web.dto.user.UserCreateReq;
import com.anomalyco.ifinbrain.web.dto.user.UserListItem;
import com.anomalyco.ifinbrain.web.dto.user.UserUpdateReq;
import com.anomalyco.ifinbrain.web.entity.SysRole;
import com.anomalyco.ifinbrain.web.entity.SysUser;
import com.anomalyco.ifinbrain.web.entity.SysUserRole;
import com.anomalyco.ifinbrain.web.repository.SysRoleRepository;
import com.anomalyco.ifinbrain.web.repository.SysUserRepository;
import com.anomalyco.ifinbrain.web.repository.SysUserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserRepository userRepo;
    private final SysUserRoleRepository userRoleRepo;
    private final SysRoleRepository roleRepo;
    private final PasswordEncoder encoder;

    @Transactional(readOnly = true)
    public Page<UserListItem> page(PageReq req) {
        Page<SysUser> page;
        if (req.getKeyword() != null && !req.getKeyword().isBlank()) {
            List<SysUser> list = userRepo.searchByKeyword(req.getKeyword());
            page = new PageImpl<>(list, PageRequest.of(0, list.size()), list.size());
        } else {
            page = userRepo.findAll(req.toPageable());
        }
        List<UserListItem> items = page.getContent().stream().map(u -> {
            List<Long> roleIds = userRoleRepo.findByUserId(u.getId()).stream()
                    .map(SysUserRole::getRoleId).toList();
            List<String> roleNames = roleIds.isEmpty() ? List.of()
                    : roleRepo.findByIdIn(roleIds).stream().map(SysRole::getName).toList();
            return UserListItem.from(u, roleIds, roleNames);
        }).collect(Collectors.toList());
        return new PageImpl<>(items, page.getPageable(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public UserListItem detail(Long id) {
        SysUser u = userRepo.findById(id).orElseThrow(() -> new BizException(404, "User not found"));
        List<Long> roleIds = userRoleRepo.findByUserId(id).stream().map(SysUserRole::getRoleId).toList();
        List<String> roleNames = roleIds.isEmpty() ? List.of()
                : roleRepo.findByIdIn(roleIds).stream().map(SysRole::getName).toList();
        return UserListItem.from(u, roleIds, roleNames);
    }

    @Transactional
    public Long create(UserCreateReq req) {
        if (userRepo.existsByUsername(req.getUsername())) {
            throw new BizException(400, "Username already exists");
        }
        SysUser u = SysUser.builder()
                .username(req.getUsername())
                .passwordHash(encoder.encode(req.getPassword()))
                .displayName(req.getDisplayName())
                .email(req.getEmail())
                .phone(req.getPhone())
                .status(req.getStatus() == null ? (short) 1 : req.getStatus())
                .build();
        userRepo.save(u);
        bindRoles(u.getId(), req.getRoleIds());
        return u.getId();
    }

    @Transactional
    public void update(Long id, UserUpdateReq req) {
        SysUser u = userRepo.findById(id).orElseThrow(() -> new BizException(404, "User not found"));
        if (req.getDisplayName() != null) u.setDisplayName(req.getDisplayName());
        if (req.getEmail() != null) u.setEmail(req.getEmail());
        if (req.getPhone() != null) u.setPhone(req.getPhone());
        if (req.getStatus() != null) u.setStatus(req.getStatus());
        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            u.setPasswordHash(encoder.encode(req.getPassword()));
        }
        userRepo.save(u);
        if (req.getRoleIds() != null) {
            userRoleRepo.deleteByUserId(id);
            userRoleRepo.flush();
            bindRoles(id, req.getRoleIds());
        }
    }

    @Transactional
    public void delete(Long id) {
        userRepo.deleteById(id);
        userRoleRepo.deleteByUserId(id);
    }

    private void bindRoles(Long userId, List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return;
        for (Long rid : roleIds) {
            userRoleRepo.save(SysUserRole.builder().userId(userId).roleId(rid).build());
        }
    }
}