package com.anomalyco.ifinbrain.web.datamgmt.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.datamgmt.dto.*;
import com.anomalyco.ifinbrain.web.datamgmt.entity.SysDatasource;
import com.anomalyco.ifinbrain.web.datamgmt.entity.SysQuery;
import com.anomalyco.ifinbrain.web.datamgmt.repository.SysDatasourceRepository;
import com.anomalyco.ifinbrain.web.datamgmt.repository.SysQueryRepository;
import com.anomalyco.ifinbrain.web.entity.SysMenu;
import com.anomalyco.ifinbrain.web.repository.SysMenuRepository;
import com.anomalyco.ifinbrain.web.security.CryptoUtil;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryService {

    private final SysQueryRepository repo;
    private final SysDatasourceRepository dsRepo;
    private final SysMenuRepository menuRepo;
    private final SqlSafetyChecker safety;
    private final QueryExecutor executor;
    private final CryptoUtil crypto;
    private final PermissionChecker perm;

    // ===================== 查询 =====================

    @Transactional(readOnly = true)
    public List<QueryDto> list() {
        perm.require("data:query:view");
        // 用 datasourceId / queryId 缓存避免 N+1
        Map<Long, SysDatasource> dsCache = new HashMap<>();
        Map<Long, SysMenu> menuCache = new HashMap<>();
        return repo.findAllByOrderByIdAsc().stream().map(q -> toDto(q, dsCache, menuCache)).toList();
    }

    @Transactional(readOnly = true)
    public QueryDto detail(Long id) {
        perm.require("data:query:view");
        SysQuery q = repo.findById(id).orElseThrow(() -> new BizException(404, "查询不存在"));
        return toDto(q, new HashMap<>(), new HashMap<>());
    }

    private QueryDto toDto(SysQuery q, Map<Long, SysDatasource> dsCache, Map<Long, SysMenu> menuCache) {
        SysDatasource ds = dsCache.computeIfAbsent(q.getDatasourceId(),
                did -> dsRepo.findById(did).orElse(null));
        QueryDto dto = QueryDto.from(q, ds == null ? "?" : ds.getName(), ds == null ? "?" : ds.getDbType());
        dto.setParamsSchema(parseParamsSchema(q.getParamsSchemaJson()));

        // 关联已发布的查询页面（如有）
        SysMenu menu = menuCache.computeIfAbsent(q.getId(),
                qid -> menuRepo.findAllPublishedPages().stream()
                        .filter(m -> qid.equals(m.getQueryId()))
                        .findFirst().orElse(null));
        if (menu != null) {
            dto.setPublishedMenuId(menu.getId());
            dto.setPublishedMenuName(menu.getName());
            dto.setPublishedMenuPath(menu.getPath());
            dto.setPublishedMenuStatus(menu.getStatus());
            dto.setPublishedMenuIcon(menu.getIcon());
            dto.setPublishedMenuSort(menu.getSort());
        }
        return dto;
    }

    private List<QueryParamSchema> parseParamsSchema(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return new tools.jackson.databind.ObjectMapper().readValue(json,
                    new tools.jackson.core.type.TypeReference<List<QueryParamSchema>>() {});
        } catch (Exception e) {
            log.warn("parseParamsSchema failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ===================== 创建 =====================

    @Transactional
    public QueryDto create(QueryCreateReq req, String createdBy) {
        perm.require("data:query:create");
        String name = req.getName().trim();
        SysDatasource ds = dsRepo.findById(req.getDatasourceId())
                .orElseThrow(() -> new BizException(400, "数据源不存在: " + req.getDatasourceId()));
        if (ds.getStatus() != null && ds.getStatus() == 0) {
            throw new BizException(400, "数据源已禁用");
        }
        if (repo.existsByDatasourceIdAndName(ds.getId(), name)) {
            throw new BizException(400, "同一数据源下查询名已存在: " + name);
        }
        safety.check(req.getSqlTemplate());
        validateParamsSchema(req.getParamsSchema());

        SysQuery e = SysQuery.builder()
                .name(name)
                .description(req.getDescription())
                .datasourceId(ds.getId())
                .sqlTemplate(req.getSqlTemplate())
                .paramsSchemaJson(serializeParamsSchema(req.getParamsSchema()))
                .status(req.getStatus() == null ? 1 : req.getStatus())
                .createdBy(createdBy == null ? "system" : createdBy)
                .build();
        e = repo.save(e);
        return toDto(e, new HashMap<>(), new HashMap<>());
    }

    // ===================== 更新 =====================

    @Transactional
    public QueryDto update(Long id, QueryUpdateReq req) {
        perm.require("data:query:update");
        SysQuery e = repo.findById(id).orElseThrow(() -> new BizException(404, "查询不存在"));

        if (req.getName() != null && !req.getName().isBlank()) {
            String name = req.getName().trim();
            Long targetDsId = req.getDatasourceId() != null ? req.getDatasourceId() : e.getDatasourceId();
            if (!name.equals(e.getName()) || !targetDsId.equals(e.getDatasourceId())) {
                if (repo.existsByDatasourceIdAndName(targetDsId, name)) {
                    throw new BizException(400, "同一数据源下查询名已存在: " + name);
                }
            }
            e.setName(name);
        }
        if (req.getDescription() != null) e.setDescription(req.getDescription());

        if (req.getDatasourceId() != null && !req.getDatasourceId().equals(e.getDatasourceId())) {
            SysDatasource ds = dsRepo.findById(req.getDatasourceId())
                    .orElseThrow(() -> new BizException(400, "数据源不存在: " + req.getDatasourceId()));
            if (ds.getStatus() != null && ds.getStatus() == 0) {
                throw new BizException(400, "数据源已禁用");
            }
            e.setDatasourceId(ds.getId());
        }
        if (req.getSqlTemplate() != null) {
            safety.check(req.getSqlTemplate());
            e.setSqlTemplate(req.getSqlTemplate());
        }
        if (req.getParamsSchema() != null) {
            validateParamsSchema(req.getParamsSchema());
            e.setParamsSchemaJson(serializeParamsSchema(req.getParamsSchema()));
        }
        if (req.getStatus() != null) e.setStatus(req.getStatus());

        e = repo.save(e);
        return toDto(e, new HashMap<>(), new HashMap<>());
    }

    // ===================== 删除 =====================

    @Transactional
    public void delete(Long id) {
        perm.require("data:query:delete");
        SysQuery e = repo.findById(id).orElseThrow(() -> new BizException(404, "查询不存在"));
        repo.delete(e);
    }

    // ===================== 运行 =====================

    @Transactional(noRollbackFor = BizException.class)
    public QueryResultDto run(Long id, QueryRunReq req, String runBy) {
        perm.require("data:query:run");
        Prepared p = prepareForRun(id);
        Map<String, String> values = req == null || req.getParamValues() == null ? Map.of() : req.getParamValues();

        QueryResultDto result;
        try {
            result = executor.execute(p.ds(), p.plain(), p.query().getSqlTemplate(), p.schema(), values);
            // 写回运行摘要
            p.query().setLastRunOk((short) 1);
            p.query().setLastRunAt(LocalDateTime.now());
            p.query().setLastRunRowCount(result.getRowCount());
            p.query().setLastRunDurationMs(result.getDurationMs());
            p.query().setLastRunMsg(result.isTruncated() ? "行数截断（上限 " + result.getRowLimit() + "）" : "ok");
        } catch (BizException ex) {
            // 失败也要落库给前端显示
            p.query().setLastRunOk((short) 0);
            p.query().setLastRunAt(LocalDateTime.now());
            p.query().setLastRunMsg(truncate(ex.getMessage(), 250));
            repo.save(p.query());
            throw ex;
        }
        repo.save(p.query());
        log.info("[query] user={} query={} ({}) ran ok rows={}", runBy, p.query().getId(), p.query().getName(), result.getRowCount());
        return result;
    }

    /**
     * 导出/报表运行前置：load + 解密 + 鉴权，不写 last_run 摘要。
     * 复用给 run() 和 export controller。
     */
    @Transactional(readOnly = true)
    public Prepared prepareForRun(Long id) {
        perm.require("data:query:run");
        SysQuery q = repo.findById(id).orElseThrow(() -> new BizException(404, "查询不存在"));
        SysDatasource ds = dsRepo.findById(q.getDatasourceId())
                .orElseThrow(() -> new BizException(400, "数据源不存在"));
        if (ds.getStatus() != null && ds.getStatus() == 0) {
            throw new BizException(400, "数据源已禁用");
        }
        String plain;
        try {
            plain = crypto.decrypt(ds.getPasswordEnc());
        } catch (Exception ex) {
            throw new BizException(500, "数据源密码无法解密（密钥可能已轮换）");
        }
        return new Prepared(q, ds, plain, parseParamsSchema(q.getParamsSchemaJson()));
    }

    /** prepareForRun 输出 */
    public record Prepared(SysQuery query, SysDatasource ds, String plain,
                           List<QueryParamSchema> schema) {}

    // ===================== 工具 =====================

    private void validateParamsSchema(List<QueryParamSchema> schema) {
        if (schema == null) return;
        Set<String> seen = new HashSet<>();
        for (QueryParamSchema p : schema) {
            if (p.getName() == null || p.getName().isBlank()) {
                throw new BizException(400, "参数 name 不能为空");
            }
            if (!seen.add(p.getName())) {
                throw new BizException(400, "参数 name 重复: " + p.getName());
            }
            String type = p.getType();
            if (type != null && !type.isBlank()) {
                if (!java.util.List.of("string", "text", "number", "date", "datetime").contains(type.toLowerCase())) {
                    throw new BizException(400, "参数 type 不合法: " + type + "（允许 string/text/number/date/datetime）");
                }
            }
        }
    }

    private String serializeParamsSchema(List<QueryParamSchema> schema) {
        if (schema == null || schema.isEmpty()) return "[]";
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(schema);
        } catch (Exception e) {
            throw new BizException(500, "paramsSchema 序列化失败: " + e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}