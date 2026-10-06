package com.anomalyco.ifinbrain.web.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.common.PageReq;
import com.anomalyco.ifinbrain.web.common.SystemPresets;
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
                .email(blankToNull(req.getEmail()))
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
        // 邮箱选填：null = 本次没带这个字段（不动）；空串 = 用户主动清空（置 NULL）。
        if (req.getEmail() != null) u.setEmail(blankToNull(req.getEmail()));
        if (req.getPhone() != null) u.setPhone(req.getPhone());
        if (req.getStatus() != null) u.setStatus(req.getStatus());
        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            // 长度下限从 DTO 移到这里（见 UserUpdateReq.password 的注释）：
            // DTO 上带 min 会把「留空不修改」的请求挡在 service 之前。
            if (req.getPassword().length() < 6) {
                throw new BizException(400, "密码长度必须在 6 和 64 之间");
            }
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
        // 先查后删：既能拿到 username 做预设校验，也顺带把「删一个不存在的 id」
        // 从 deleteById 抛 EmptyResultDataAccessException（最后会变成 500）
        // 修正成明确的 404。
        SysUser u = userRepo.findById(id).orElseThrow(() -> new BizException(404, "User not found"));
        if (SystemPresets.ADMIN_USERNAME.equals(u.getUsername())) {
            throw new BizException(409, "系统预设账号 " + SystemPresets.ADMIN_USERNAME + " 不允许删除");
        }
        userRepo.delete(u);
        userRoleRepo.deleteByUserId(id);
    }

    /** 邮箱选填：空白统一落 NULL，避免库里留一堆空串被当成「填过」。 */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private void bindRoles(Long userId, List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return;
        for (Long rid : roleIds) {
            userRoleRepo.save(SysUserRole.builder().userId(userId).roleId(rid).build());
        }
    }
}