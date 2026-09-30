package com.anomalyco.ifinbrain.web.design.form.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.design.form.dto.FormDesignCreateReq;
import com.anomalyco.ifinbrain.web.design.form.dto.FormDesignDto;
import com.anomalyco.ifinbrain.web.design.form.dto.FormDesignUpdateReq;
import com.anomalyco.ifinbrain.web.design.form.dto.FormFieldSchema;
import com.anomalyco.ifinbrain.web.design.form.entity.SysFormDesign;
import com.anomalyco.ifinbrain.web.design.form.enums.FieldType;
import com.anomalyco.ifinbrain.web.design.form.repository.SysFormDesignRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class FormDesignService {

    private final SysFormDesignRepository repo;
    private final PermissionChecker perm;

    // ===================== 查询 =====================

    @Transactional(readOnly = true)
    public List<FormDesignDto> list() {
        perm.require("design:form:view");
        return repo.findAllByOrderByIdAsc().stream().map(e -> toDto(e)).toList();
    }

    @Transactional(readOnly = true)
    public FormDesignDto detail(Long id) {
        perm.require("design:form:view");
        SysFormDesign e = repo.findById(id).orElseThrow(() -> new BizException(404, "表单不存在"));
        return toDto(e);
    }

    /** 按 code 读取（供流程节点挂表单时使用） */
    @Transactional(readOnly = true)
    public FormDesignDto detailByCode(String code) {
        SysFormDesign e = repo.findByCode(code).orElseThrow(() -> new BizException(404, "表单不存在: " + code));
        return toDto(e);
    }

    private FormDesignDto toDto(SysFormDesign e) {
        List<FormFieldSchema> fields = parseFields(e.getFieldsJson());
        return FormDesignDto.from(e, fields);
    }

    // ===================== 创建 =====================

    @Transactional
    public FormDesignDto create(FormDesignCreateReq req, String createdBy) {
        perm.require("design:form:create");
        String code = req.getCode().trim();
        if (repo.existsByCode(code)) {
            throw new BizException(400, "表单 code 已存在: " + code);
        }
        validateFields(req.getFields());

        SysFormDesign e = SysFormDesign.builder()
                .code(code)
                .name(req.getName().trim())
                .description(req.getDescription())
                .fieldsJson(serializeFields(req.getFields()))
                .status(req.getStatus() == null ? 0 : req.getStatus())
                .createdBy(createdBy == null ? "system" : createdBy)
                .build();
        e = repo.save(e);
        return toDto(e);
    }

    // ===================== 更新 =====================

    @Transactional
    public FormDesignDto update(Long id, FormDesignUpdateReq req) {
        perm.require("design:form:update");
        SysFormDesign e = repo.findById(id).orElseThrow(() -> new BizException(404, "表单不存在"));

        if (req.getCode() != null && !req.getCode().isBlank()) {
            String newCode = req.getCode().trim();
            if (!newCode.equals(e.getCode()) && repo.existsByCodeAndIdNot(newCode, id)) {
                throw new BizException(400, "表单 code 已存在: " + newCode);
            }
            e.setCode(newCode);
        }
        if (req.getName() != null && !req.getName().isBlank()) {
            e.setName(req.getName().trim());
        }
        if (req.getDescription() != null) {
            e.setDescription(req.getDescription());
        }
        if (req.getFields() != null) {
            validateFields(req.getFields());
            e.setFieldsJson(serializeFields(req.getFields()));
        }
        if (req.getStatus() != null) e.setStatus(req.getStatus());

        e = repo.save(e);
        return toDto(e);
    }

    // ===================== 删除 =====================

    @Transactional
    public void delete(Long id) {
        perm.require("design:form:delete");
        SysFormDesign e = repo.findById(id).orElseThrow(() -> new BizException(404, "表单不存在"));
        repo.delete(e);
    }

    // ===================== 发布 / 反发布 =====================

    @Transactional
    public FormDesignDto publish(Long id) {
        perm.require("design:form:publish");
        SysFormDesign e = repo.findById(id).orElseThrow(() -> new BizException(404, "表单不存在"));
        // 发布时校验当前 schema 合法
        validateFields(parseFields(e.getFieldsJson()));
        e.setStatus((short) 1);
        e = repo.save(e);
        return toDto(e);
    }

    @Transactional
    public FormDesignDto unpublish(Long id) {
        perm.require("design:form:publish");
        SysFormDesign e = repo.findById(id).orElseThrow(() -> new BizException(404, "表单不存在"));
        e.setStatus((short) 0);
        e = repo.save(e);
        return toDto(e);
    }

    // ===================== 工具 =====================

    /** 校验字段数组（key 唯一、type 合法、options 必填项） */
    private void validateFields(List<FormFieldSchema> fields) {
        if (fields == null) return;
        Set<String> keys = new HashSet<>();
        for (FormFieldSchema f : fields) {
            if (f.getKey() == null || f.getKey().isBlank()) {
                throw new BizException(400, "字段 key 不能为空");
            }
            if (!keys.add(f.getKey())) {
                throw new BizException(400, "字段 key 重复: " + f.getKey());
            }
            if (f.getType() == null) {
                throw new BizException(400, "字段 type 不能为空: key=" + f.getKey());
            }
            // type 必须是合法枚举（Jackson 反序列化时已校验，但保险起见再 assert 一次）
            try {
                FieldType.valueOf(f.getType().name());
            } catch (Exception ex) {
                throw new BizException(400, "字段 type 不合法: " + f.getType() + "（key=" + f.getKey() + "）");
            }
            if (f.getType().needsOptions()) {
                if (f.getOptions() == null || f.getOptions().isEmpty()) {
                    throw new BizException(400, "字段 type=" + f.getType() + " 必须配置 options（key=" + f.getKey() + "）");
                }
            }
        }
    }

    private List<FormFieldSchema> parseFields(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return new tools.jackson.databind.ObjectMapper().readValue(json,
                    new tools.jackson.core.type.TypeReference<List<FormFieldSchema>>() {});
        } catch (Exception e) {
            log.warn("parseFields failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String serializeFields(List<FormFieldSchema> fields) {
        if (fields == null || fields.isEmpty()) return "{\"version\":1,\"fields\":[]}";
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(fields);
        } catch (Exception e) {
            throw new BizException(500, "fields 序列化失败: " + e.getMessage());
        }
    }
}