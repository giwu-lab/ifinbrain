package com.anomalyco.ifinbrain.web.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.dto.role.RoleCreateReq;
import com.anomalyco.ifinbrain.web.dto.role.RoleListItem;
import com.anomalyco.ifinbrain.web.dto.role.RoleUpdateReq;
import com.anomalyco.ifinbrain.web.entity.SysPermission;
import com.anomalyco.ifinbrain.web.entity.SysRole;
import com.anomalyco.ifinbrain.web.entity.SysRolePerm;
import com.anomalyco.ifinbrain.web.repository.SysPermissionRepository;
import com.anomalyco.ifinbrain.web.repository.SysRolePermRepository;
import com.anomalyco.ifinbrain.web.repository.SysRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleService {

    private final SysRoleRepository roleRepo;
    private final SysRolePermRepository rolePermRepo;
    private final SysPermissionRepository permRepo;

    @Transactional(readOnly = true)
    public List<RoleListItem> list() {
        List<SysRole> roles = roleRepo.findAll();
        return roles.stream().map(r -> {
            List<Long> permIds = rolePermRepo.findByRoleId(r.getId()).stream()
                    .map(SysRolePerm::getPermId).toList();
            List<String> permCodes = permIds.isEmpty() ? List.of()
                    : permRepo.findByIdIn(permIds).stream().map(SysPermission::getCode).toList();
            return RoleListItem.from(r, permIds, permCodes);
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public RoleListItem detail(Long id) {
        SysRole r = roleRepo.findById(id).orElseThrow(() -> new BizException(404, "Role not found"));
        List<Long> permIds = rolePermRepo.findByRoleId(id).stream().map(SysRolePerm::getPermId).toList();
        List<String> permCodes = permIds.isEmpty() ? List.of()
                : permRepo.findByIdIn(permIds).stream().map(SysPermission::getCode).toList();
        return RoleListItem.from(r, permIds, permCodes);
    }

    @Transactional
    public Long create(RoleCreateReq req) {
        roleRepo.findByCode(req.getCode()).ifPresent(x -> {
            throw new BizException(400, "Role code already exists");
        });
        SysRole r = SysRole.builder()
                .code(req.getCode())
                .name(req.getName())
                .remark(req.getRemark())
                .status(req.getStatus() == null ? (short) 1 : req.getStatus())
                .build();
        roleRepo.save(r);
        bindPerms(r.getId(), req.getPermissionIds());
        return r.getId();
    }

    @Transactional
    public void update(Long id, RoleUpdateReq req) {
        SysRole r = roleRepo.findById(id).orElseThrow(() -> new BizException(404, "Role not found"));
        if (req.getName() != null) r.setName(req.getName());
        if (req.getRemark() != null) r.setRemark(req.getRemark());
        if (req.getStatus() != null) r.setStatus(req.getStatus());
        roleRepo.save(r);
        if (req.getPermissionIds() != null) {
            rolePermRepo.deleteByRoleId(id);
            rolePermRepo.flush();
            bindPerms(id, req.getPermissionIds());
        }
    }

    @Transactional
    public void delete(Long id) {
        roleRepo.deleteById(id);
        rolePermRepo.deleteByRoleId(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> allPermissions() {
        return permRepo.findAll().stream()
                .map(p -> Map.<String, Object>of(
                        "id", p.getId(),
                        "code", p.getCode(),
                        "name", p.getName(),
                        "type", p.getType()))
                .collect(Collectors.toList());
    }

    private void bindPerms(Long roleId, List<Long> permIds) {
        if (permIds == null || permIds.isEmpty()) return;
        for (Long pid : permIds) {
            rolePermRepo.save(SysRolePerm.builder().roleId(roleId).permId(pid).build());
        }
    }
}