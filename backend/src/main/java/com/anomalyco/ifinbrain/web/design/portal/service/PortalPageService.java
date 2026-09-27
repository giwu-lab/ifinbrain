package com.anomalyco.ifinbrain.web.design.portal.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.design.portal.compiler.LayoutSchemaCache;
import com.anomalyco.ifinbrain.web.design.portal.compiler.LayoutSchemaValidator;
import com.anomalyco.ifinbrain.web.design.portal.dto.PortalPageCreateReq;
import com.anomalyco.ifinbrain.web.design.portal.dto.PortalPageDto;
import com.anomalyco.ifinbrain.web.design.portal.dto.PortalPageUpdateReq;
import com.anomalyco.ifinbrain.web.design.portal.dto.PortalResolveResp;
import com.anomalyco.ifinbrain.web.design.portal.entity.SysPortalPage;
import com.anomalyco.ifinbrain.web.design.portal.repository.SysPortalPageRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PortalPageService {

    private static final String DEFAULT_LAYOUT =
            "{\"version\":1,\"root\":{\"id\":\"root\",\"type\":\"page\",\"props\":{\"cols\":12,\"gap\":16,\"padding\":24},\"children\":[]}}";

    private final SysPortalPageRepository repo;
    private final PermissionChecker perm;
    private final LayoutSchemaValidator validator;
    private final LayoutSchemaCache cache;

    // ===================== 查询 =====================

    @Transactional(readOnly = true)
    public List<PortalPageDto> list() {
        perm.require("design:page:view");
        return repo.findAllByOrderByIdAsc().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public PortalPageDto detail(Long id) {
        perm.require("design:page:view");
        SysPortalPage e = repo.findById(id).orElseThrow(() -> new BizException(404, "门户页不存在"));
        return toDto(e);
    }

    @Transactional(readOnly = true)
    public PortalResolveResp resolveForCurrentUser() {
        // 此接口路由暴露给登录用户（不限 design:page:view），仅消费 status=1 且角色命中
        String currentRoleCodes = perm.currentRoleCodes();
        if (currentRoleCodes == null || currentRoleCodes.isBlank()) {
            return PortalResolveResp.builder().hit(false).build();
        }
        List<String> userRoles = List.of(currentRoleCodes.split(","));
        for (SysPortalPage p : repo.findAllByOrderByIdAsc()) {
            if (p.getStatus() == null || p.getStatus() != 1) continue;
            List<String> bindings = parseRoleBindings(p.getRoleBindings());
            if (bindings.isEmpty()) continue;
            for (String r : userRoles) {
                if (bindings.contains(r.trim())) {
                    // 走缓存编译：避免每次请求都 parse JSON（文章 §11.2 多级缓存）
                    cache.getOrCompile(p.getId(), p.getConfigVersion(), p.getLayoutSchema());
                    log.info("[portal-page] resolve traceId={} id={} v={} roles={}", traceId(),
                            p.getId(), p.getConfigVersion(), currentRoleCodes);
                    return PortalResolveResp.builder()
                            .hit(true)
                            .id(p.getId())
                            .code(p.getCode())
                            .name(p.getName())
                            .layoutSchema(p.getLayoutSchema())
                            .build();
                }
            }
        }
        return PortalResolveResp.builder().hit(false).build();
    }

    // ===================== 创建 =====================

    @Transactional
    public PortalPageDto create(PortalPageCreateReq req, String createdBy) {
        perm.require("design:page:update");
        String code = req.getCode().trim();
        if (repo.existsByCode(code)) {
            throw new BizException(400, "门户页 code 已存在: " + code);
        }
        SysPortalPage e = SysPortalPage.builder()
                .code(code)
                .name(req.getName().trim())
                .description(req.getDescription())
                .layoutSchema(validator.validate(req.getLayoutSchema() == null || req.getLayoutSchema().isBlank()
                        ? DEFAULT_LAYOUT : req.getLayoutSchema()))
                .configVersion(1)
                .status(req.getStatus() != null ? req.getStatus() : (short) 0)
                .roleBindings(serializeRoleBindings(req.getRoleBindings()))
                .createdBy(createdBy == null ? "system" : createdBy)
                .build();
        e = repo.save(e);
        log.info("[portal-page] create traceId={} id={} code={} by={}", traceId(), e.getId(), e.getCode(), e.getCreatedBy());
        return toDto(e);
    }

    // ===================== 更新 =====================

    @Transactional
    public PortalPageDto update(Long id, PortalPageUpdateReq req) {
        perm.require("design:page:update");
        SysPortalPage e = repo.findById(id).orElseThrow(() -> new BizException(404, "门户页不存在"));

        if (req.getName() != null && !req.getName().isBlank()) {
            e.setName(req.getName().trim());
        }
        if (req.getDescription() != null) {
            e.setDescription(req.getDescription());
        }
        if (req.getLayoutSchema() != null) {
            e.setLayoutSchema(validator.validate(req.getLayoutSchema()));
            e.setConfigVersion(e.getConfigVersion() + 1);
            cache.evict(id); // 写操作触发缓存失效 —— 文章 §11.3 热更新要点
        }
        if (req.getRoleBindings() != null) {
            e.setRoleBindings(serializeRoleBindings(req.getRoleBindings()));
        }
        if (req.getStatus() != null) {
            Short old = e.getStatus();
            e.setStatus(req.getStatus());
            // 仅在 0→1 边沿记录 published_at
            if ((old == null || old == 0) && req.getStatus() == 1 && e.getPublishedAt() == null) {
                e.setPublishedAt(LocalDateTime.now());
            }
        }
        e = repo.save(e);
        log.info("[portal-page] update traceId={} id={} v{} status={}", traceId(), id, e.getConfigVersion(), e.getStatus());
        return toDto(e);
    }

    // ===================== 发布 / 取消 =====================

    @Transactional
    public PortalPageDto publish(Long id) {
        perm.require("design:page:publish");
        SysPortalPage e = repo.findById(id).orElseThrow(() -> new BizException(404, "门户页不存在"));
        e.setStatus((short) 1);
        if (e.getPublishedAt() == null) e.setPublishedAt(LocalDateTime.now());
        e = repo.save(e);
        return toDto(e);
    }

    @Transactional
    public PortalPageDto unpublish(Long id) {
        perm.require("design:page:publish");
        SysPortalPage e = repo.findById(id).orElseThrow(() -> new BizException(404, "门户页不存在"));
        e.setStatus((short) 0);
        e = repo.save(e);
        return toDto(e);
    }

    // ===================== 删除 =====================

    @Transactional
    public void delete(Long id) {
        perm.require("design:page:delete");
        SysPortalPage e = repo.findById(id).orElseThrow(() -> new BizException(404, "门户页不存在"));
        repo.delete(e);
    }

    // ===================== 工具 =====================

    private PortalPageDto toDto(SysPortalPage e) {
        return PortalPageDto.from(e, parseRoleBindings(e.getRoleBindings()));
    }

    /** 当前请求的 traceId（简化版：每操作一个 UUID）—— 文章 §16.2 可观测性建议 */
    private String traceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private List<String> parseRoleBindings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return new tools.jackson.databind.ObjectMapper().readValue(json,
                    new tools.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("parseRoleBindings failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String serializeRoleBindings(List<String> roles) {
        if (roles == null) return "[]";
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(roles);
        } catch (Exception e) {
            throw new BizException(500, "roleBindings 序列化失败: " + e.getMessage());
        }
    }
}