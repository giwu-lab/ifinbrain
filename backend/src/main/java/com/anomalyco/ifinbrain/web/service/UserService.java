package com.anomalyco.ifinbrain.web.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.common.PageReq;
import com.anomalyco.ifinbrain.web.common.SystemPresets;
import com.anomalyco.ifinbrain.web.dto.user.UserCreateReq;
import com.anomalyco.ifinbrain.web.dto.user.UserListItem;
import com.anomalyco.ifinbrain.web.dto.user.UserUpdateReq;
import com.anomalyco.ifinbrain.web.entity.SysRole;
import com.anomalyco.ifinbrain.web.entity.SysDept;
import com.anomalyco.ifinbrain.web.entity.SysUser;
import com.anomalyco.ifinbrain.web.entity.SysUserRole;
import com.anomalyco.ifinbrain.web.repository.SysDeptRepository;
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
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserRepository userRepo;
    private final SysUserRoleRepository userRoleRepo;
    private final SysRoleRepository roleRepo;
    private final SysDeptRepository deptRepo;
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
        List<SysUser> users = page.getContent();
        // 一次把本页涉及的所有部门捞出来，避免逐用户查部门造成 N+1
        Map<Long, String> deptNames = resolveDeptNames(users);
        List<UserListItem> items = users.stream().map(u -> {
            List<Long> roleIds = userRoleRepo.findByUserId(u.getId()).stream()
                    .map(SysUserRole::getRoleId).toList();
            List<String> roleNames = roleIds.isEmpty() ? List.of()
                    : roleRepo.findByIdIn(roleIds).stream().map(SysRole::getName).toList();
            // deptId 可能为 null（未归属部门），查表前先挡一下
            String deptName = u.getDeptId() == null ? null : deptNames.get(u.getDeptId());
            return UserListItem.from(u, roleIds, roleNames, deptName);
        }).collect(Collectors.toList());
        return new PageImpl<>(items, page.getPageable(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public UserListItem detail(Long id) {
        SysUser u = userRepo.findById(id).orElseThrow(() -> new BizException(404, "User not found"));
        List<Long> roleIds = userRoleRepo.findByUserId(id).stream().map(SysUserRole::getRoleId).toList();
        List<String> roleNames = roleIds.isEmpty() ? List.of()
                : roleRepo.findByIdIn(roleIds).stream().map(SysRole::getName).toList();
        return UserListItem.from(u, roleIds, roleNames, deptNameOf(u.getDeptId()));
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
                .deptId(resolveDeptId(req.getDeptId()))
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
        // 部门三态：null = 不动；0 = 清空；>0 = 改挂。见 UserUpdateReq.deptId。
        if (req.getDeptId() != null) u.setDeptId(resolveDeptId(req.getDeptId()));
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

    /**
     * 部门 id 归一化：null / 0 = 不归属任何部门（落 NULL）；正数必须在 sys_dept 里存在。
     *
     * <p>sys_user.dept_id 上没有外键（与本库其它关联表一致），所以这里必须自己校验，
     * 否则一次手滑的请求就能写进一个不存在的部门 id，之后谁也查不出这个用户属于哪。
     */
    private Long resolveDeptId(Long deptId) {
        if (deptId == null || deptId == 0L) return null;
        if (!deptRepo.existsById(deptId)) {
            throw new BizException(400, "部门不存在: " + deptId);
        }
        return deptId;
    }

    private String deptNameOf(Long deptId) {
        if (deptId == null) return null;
        return deptRepo.findById(deptId).map(SysDept::getName).orElse(null);
    }

    /**
     * 批量解析：一次 IN 查询拿全本页的部门名，避免逐用户查库的 N+1。
     *
     * <p>返回 {@code HashMap} 而不是 {@code Map.of()}：后者是不可变的空 Map，
     * {@code MapN.get(null)} 会直接抛 NPE（内部走 Objects.requireNonNull），
     * 而「未归属部门」的用户 deptId 恰恰是 null，列表页会整页 500。
     */
    private Map<Long, String> resolveDeptNames(List<SysUser> users) {
        Map<Long, String> out = new HashMap<>();
        List<Long> ids = users.stream()
                .map(SysUser::getDeptId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) return out;
        for (SysDept d : deptRepo.findAllById(ids)) {
            out.put(d.getId(), d.getName());
        }
        return out;
    }

    private void bindRoles(Long userId, List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return;
        for (Long rid : roleIds) {
            userRoleRepo.save(SysUserRole.builder().userId(userId).roleId(rid).build());
        }
    }
}