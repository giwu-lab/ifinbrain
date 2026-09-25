package com.anomalyco.ifinbrain.web.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.dto.auth.LoginReq;
import com.anomalyco.ifinbrain.web.dto.auth.LoginResp;
import com.anomalyco.ifinbrain.web.dto.auth.MenuNode;
import com.anomalyco.ifinbrain.web.dto.auth.UserInfo;
import com.anomalyco.ifinbrain.web.entity.*;
import com.anomalyco.ifinbrain.web.repository.*;
import com.anomalyco.ifinbrain.web.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserRepository userRepo;
    private final SysUserRoleRepository userRoleRepo;
    private final SysRoleRepository roleRepo;
    private final SysRolePermRepository rolePermRepo;
    private final SysPermissionRepository permRepo;
    private final SysMenuRepository menuRepo;
    private final SysRefreshTokenRepository refreshTokenRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    private static final SecureRandom RNG = new SecureRandom();

    @Transactional
    public LoginResp login(LoginReq req) {
        SysUser user = userRepo.findByUsername(req.getUsername())
                .orElseThrow(() -> new BizException(401, "Invalid username or password"));
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(403, "User is disabled");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BizException(401, "Invalid username or password");
        }
        user.setLastLoginAt(LocalDateTime.now());
        userRepo.save(user);
        return issue(user);
    }

    @Transactional
    public LoginResp refresh(String refreshToken) {
        String hash = sha256(refreshToken);
        SysRefreshToken row = refreshTokenRepo.findByTokenHash(hash)
                .orElseThrow(() -> new BizException(401, "Invalid refresh token"));
        if (row.getRevoked() == 1 || row.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BizException(401, "Refresh token expired or revoked");
        }
        SysUser user = userRepo.findById(row.getUserId())
                .orElseThrow(() -> new BizException(401, "User not found"));
        // rotate
        row.setRevoked((short) 1);
        refreshTokenRepo.save(row);
        return issue(user);
    }

    @Transactional
    public void logout(Long userId) {
        refreshTokenRepo.revokeAllByUser(userId);
    }

    @Transactional(readOnly = true)
    public UserInfo me(Long userId) {
        SysUser user = userRepo.findById(userId)
                .orElseThrow(() -> new BizException(404, "User not found"));
        List<Long> roleIds = userRoleRepo.findByUserId(userId).stream()
                .map(SysUserRole::getRoleId).toList();
        List<String> roleCodes = roleIds.isEmpty() ? List.of()
                : roleRepo.findByIdIn(roleIds).stream().map(SysRole::getCode).toList();
        List<Long> permIds = roleIds.isEmpty() ? List.of() : rolePermRepo.findPermIdsByRoleIds(roleIds);
        List<String> permCodes = permIds.isEmpty() ? List.of()
                : permRepo.findByIdIn(permIds).stream().map(SysPermission::getCode).toList();
        List<MenuNode> menus = loadMenus(roleIds);
        return UserInfo.builder()
                .id(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .roles(roleCodes)
                .permissions(permCodes)
                .menus(menus)
                .build();
    }

    private LoginResp issue(SysUser user) {
        List<Long> roleIds = userRoleRepo.findByUserId(user.getId()).stream()
                .map(SysUserRole::getRoleId).toList();
        List<String> roleCodes = roleIds.isEmpty() ? List.of()
                : roleRepo.findByIdIn(roleIds).stream().map(SysRole::getCode).toList();
        List<Long> permIds = roleIds.isEmpty() ? List.of() : rolePermRepo.findPermIdsByRoleIds(roleIds);
        List<String> permCodes = permIds.isEmpty() ? List.of()
                : permRepo.findByIdIn(permIds).stream().map(SysPermission::getCode).toList();

        String access = jwtUtil.issueAccess(user.getId(), user.getUsername(), roleCodes, permCodes);
        String refresh = newRefreshToken();
        SysRefreshToken row = SysRefreshToken.builder()
                .userId(user.getId())
                .tokenHash(sha256(refresh))
                .expiresAt(LocalDateTime.now().plusDays(7))
                .revoked((short) 0)
                .build();
        refreshTokenRepo.save(row);

        return LoginResp.builder()
                .accessToken(access)
                .refreshToken(refresh)
                .accessTtlSeconds(jwtUtil.accessTtlSeconds())
                .refreshTtlSeconds(jwtUtil.refreshTtlSeconds())
                .user(UserInfo.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .displayName(user.getDisplayName())
                        .email(user.getEmail())
                        .phone(user.getPhone())
                        .roles(roleCodes)
                        .permissions(permCodes)
                        .menus(loadMenus(roleIds))
                        .build())
                .build();
    }

    @Transactional(readOnly = true)
    public List<MenuNode> loadMenus(List<Long> roleIds) {
        List<SysMenu> all = menuRepo.findAccessibleMenus(roleIds);
        Map<Long, MenuNode> nodeMap = new LinkedHashMap<>();
        for (SysMenu m : all) {
            MenuNode node = new MenuNode();
            node.setId(m.getId());
            node.setParentId(m.getParentId());
            node.setName(m.getName());
            node.setPath(m.getPath());
            node.setComponent(m.getComponent());
            node.setIcon(m.getIcon());
            node.setSort(m.getSort());
            node.setPermission(m.getPermission());
            node.setType(m.getType());
            nodeMap.put(m.getId(), node);
        }
        // build tree
        List<MenuNode> roots = new ArrayList<>();
        for (MenuNode n : nodeMap.values()) {
            if (n.getParentId() == null || n.getParentId() == 0L) {
                roots.add(n);
            } else if (nodeMap.containsKey(n.getParentId())) {
                nodeMap.get(n.getParentId()).getChildren().add(n);
            } else {
                roots.add(n);
            }
        }
        // sort
        sortTree(roots);
        return roots;
    }

    private void sortTree(List<MenuNode> nodes) {
        nodes.sort(Comparator.comparing(MenuNode::getSort,
                Comparator.nullsLast(Comparator.naturalOrder())));
        for (MenuNode n : nodes) {
            if (n.getChildren() != null && !n.getChildren().isEmpty()) {
                sortTree(n.getChildren());
            }
        }
    }

    private String newRefreshToken() {
        byte[] buf = new byte[48];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes());
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}