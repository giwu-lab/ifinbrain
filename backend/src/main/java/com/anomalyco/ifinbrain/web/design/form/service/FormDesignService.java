package com.anomalyco.ifinbrain.web.design.form.service;

import com.anomalyco.ifinbrain.web.bi.dto.BiDataQueryReq;
import com.anomalyco.ifinbrain.web.bi.dto.BiDataResp;
import com.anomalyco.ifinbrain.web.bi.service.BiDataService;
import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.datamgmt.dto.QueryResultDto;
import com.anomalyco.ifinbrain.web.design.form.dto.FormDesignCreateReq;
import com.anomalyco.ifinbrain.web.design.form.dto.FormDesignDto;
import com.anomalyco.ifinbrain.web.design.form.dto.FormDesignUpdateReq;
import com.anomalyco.ifinbrain.web.design.form.dto.FormFieldColumn;
import com.anomalyco.ifinbrain.web.design.form.dto.FormFieldOption;
import com.anomalyco.ifinbrain.web.design.form.dto.FormFieldOptionsSource;
import com.anomalyco.ifinbrain.web.design.form.dto.FormFieldSchema;
import com.anomalyco.ifinbrain.web.design.form.entity.SysFormDesign;
import com.anomalyco.ifinbrain.web.design.form.enums.FieldType;
import com.anomalyco.ifinbrain.web.design.form.repository.SysFormDesignRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class FormDesignService {

    private final SysFormDesignRepository repo;
    private final PermissionChecker perm;
    /** 只借用取数执行逻辑（queryAuthorized 不鉴权，鉴权在本类里做） */
    private final BiDataService biDataService;

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
                // 静态 options 与动态 optionsSource 二选一：配了 source 就以查询为准
                boolean hasSource = f.getOptionsSource() != null;
                if (!hasSource && (f.getOptions() == null || f.getOptions().isEmpty())) {
                    throw new BizException(400, "字段 type=" + f.getType() + " 必须配置 options 或 optionsSource（key=" + f.getKey() + "）");
                }
                if (hasSource) {
                    validateOptionsSource(f.getOptionsSource(), "（key=" + f.getKey() + "）");
                }
            }
            if (f.getType() == FieldType.TABLE) {
                validateColumns(f);
            }
        }
    }

    /**
     * 表格列校验：必须有列、列 key 唯一、列 type 合法且能做列（排除 DIVIDER）、
     * select/radio/checkbox 列必须配 options。
     *
     * <p>这几条都是「保存时兜住」——漏了不会在运行时炸，只会让填表人看到一个
     * 渲染不出来的表格，或者一个永远选不出东西的下拉。
     */
    private void validateColumns(FormFieldSchema f) {
        List<FormFieldColumn> cols = f.getColumns();
        if (cols == null || cols.isEmpty()) {
            throw new BizException(400, "表格字段必须至少配置一列（key=" + f.getKey() + "）");
        }
        Set<String> colKeys = new HashSet<>();
        for (FormFieldColumn c : cols) {
            String ck = c.getKey() == null ? "" : c.getKey().trim();
            if (ck.isBlank()) {
                throw new BizException(400, "表格列 key 不能为空（key=" + f.getKey() + "）");
            }
            if (!colKeys.add(ck)) {
                throw new BizException(400, "表格列 key 重复: " + ck + "（key=" + f.getKey() + "）");
            }
            FieldType ct = c.getType() == null ? FieldType.TEXT : c.getType();
            if (!ct.usableAsColumn()) {
                throw new BizException(400, ct + " 不能作为表格列类型（列 key=" + ck + "，字段 key=" + f.getKey() + "）");
            }
            if (ct.needsOptions() && (c.getOptions() == null || c.getOptions().isEmpty())) {
                throw new BizException(400, "表格列 type=" + ct + " 必须配置 options（列 key=" + ck + "）");
            }
        }
    }

    /**
     * 动态选项源校验。
     *
     * <p>这几条都在「保存时兜住」——漏了不会在运行时炸，只会让填表人看到一个
     * 永远空着的下拉，且报错发生在他填表的时候而不是设计的时候。
     *
     * @param where 报错时的定位串，直接拼进错误消息
     */
    private void validateOptionsSource(FormFieldOptionsSource src, String where) {
        if (src == null) {
            throw new BizException(400, "optionsSource 为空" + where);
        }
        if (src.getQueryId() == null || src.getQueryId() <= 0) {
            throw new BizException(400, "optionsSource.queryId 无效" + where);
        }
        if (src.getLabelField() == null || src.getLabelField().isBlank()) {
            throw new BizException(400, "optionsSource.labelField 不能为空" + where);
        }
        if (src.getValueField() == null || src.getValueField().isBlank()) {
            throw new BizException(400, "optionsSource.valueField 不能为空" + where);
        }
    }

    /**
     * 按动态选项源取回选项列表。
     *
     * <p>权限用 {@code design:form:view} 而不是复用 BI 的 {@code bi:dataset:view}：
     * 填表人只该有填表的权限，不该被要求持有大屏数据权限。取数执行逻辑复用
     * {@code BiDataService#queryAuthorized}（该方法不做鉴权，由本方法负责）。
     *
     * <p>安全边界与 BI 组件绑 query 完全一致：**填表人不能自己挑 query**，
     * queryId 来自设计期固化的 schema，填表请求里带什么都不改变取数目标。
     */
    @Transactional(readOnly = true)
    public List<FormFieldOption> options(FormFieldOptionsSource src) {
        perm.require("design:form:view");
        validateOptionsSource(src, "");
        BiDataQueryReq req = new BiDataQueryReq();
        req.setQueryId(src.getQueryId());
        req.setParams(src.getParams());
        req.setCacheSec(src.getCacheSec());

        BiDataResp resp = biDataService.queryAuthorized(req);

        int labelIdx = columnIndex(resp.getColumns(), src.getLabelField());
        int valueIdx = columnIndex(resp.getColumns(), src.getValueField());
        if (labelIdx < 0 || valueIdx < 0) {
            List<String> actual = resp.getColumns().stream().map(c -> c.getKey()).toList();
            throw new BizException(400, "查询结果里找不到列：labelField=" + src.getLabelField()
                    + " valueField=" + src.getValueField() + "（实际返回 " + actual + "）");
        }

        List<FormFieldOption> out = new ArrayList<>(resp.getRowCount());
        for (List<Object> row : resp.getRows()) {
            Object lv = row.get(labelIdx);
            Object vv = row.get(valueIdx);
            if (lv == null && vv == null) continue;   // 整行空，跳过
            out.add(FormFieldOption.builder()
                    .label(lv == null ? "" : String.valueOf(lv))
                    .value(vv == null ? "" : String.valueOf(vv))
                    .build());
        }
        return out;
    }

    /** 按列名找下标；找不到返回 -1 */
    private int columnIndex(List<QueryResultDto.Column> columns, String key) {
        if (columns == null || key == null) return -1;
        for (int i = 0; i < columns.size(); i++) {
            if (key.equals(columns.get(i).getKey())) return i;
        }
        return -1;
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
        // 空数组就写空数组：早先这里写的是 {"version":1,"fields":[]}，
        // 而 parseFields 按 List 读 —— 空表单存进去再读出来会解析失败（只 warn 后
        // 兜成空 list，碰巧看不出错）。写回与读入同构，省掉这个不一致。
        if (fields == null || fields.isEmpty()) return "[]";
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(fields);
        } catch (Exception e) {
            throw new BizException(500, "fields 序列化失败: " + e.getMessage());
        }
    }
}