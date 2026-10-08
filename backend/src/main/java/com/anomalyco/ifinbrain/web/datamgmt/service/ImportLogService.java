package com.anomalyco.ifinbrain.web.datamgmt.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.datamgmt.dto.ImportErrorRow;
import com.anomalyco.ifinbrain.web.datamgmt.dto.ImportLogDto;
import com.anomalyco.ifinbrain.web.datamgmt.entity.SysImportLog;
import com.anomalyco.ifinbrain.web.datamgmt.repository.SysImportLogRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 导入日志（审计）读写。
 *
 * <p>日志只追加不修改 —— 这是它作为「谁往这张表写了 3 万行」的证据链的前提。
 * 唯一的写操作是删除（{@code data:import:log:delete}），默认只给 admin。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportLogService {

    private static final int MAX_PAGE_SIZE = 200;

    private final SysImportLogRepository repo;
    private final PermissionChecker perm;

    /**
     * Jackson 3 的 ObjectMapper（项目统一用 tools.jackson.*，与 BiDashboardService / QueryService 一致）。
     *
     * <p>刻意<b>不注入 bean</b>：Spring Boot 4 不会把 ObjectMapper 注册成可注入的 bean，
     * 写了构造器参数会在启动期直接炸（"No qualifying bean of type ObjectMapper"）。
     * ObjectMapper 线程安全，建一个静态实例即可 —— 与 BiDashboardService 同样的做法。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 分页查询导入历史（可按数据源过滤） */
    @Transactional(readOnly = true)
    public Map<String, Object> list(Long datasourceId, int page, int size) {
        perm.require("data:import:view");
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 1);
        PageRequest pr = PageRequest.of(safePage - 1, safeSize);

        Page<SysImportLog> result = (datasourceId == null)
                ? repo.findAllByOrderByIdDesc(pr)
                : repo.findByDatasourceIdOrderByIdDesc(datasourceId, pr);

        Map<String, Object> resp = new HashMap<>();
        resp.put("items", result.getContent().stream().map(this::toDto).toList());
        resp.put("total", result.getTotalElements());
        resp.put("page", safePage);
        resp.put("size", safeSize);
        return resp;
    }

    @Transactional(readOnly = true)
    public ImportLogDto detail(Long id) {
        perm.require("data:import:view");
        return toDto(repo.findById(id).orElseThrow(() -> new BizException(404, "导入记录不存在")));
    }

    @Transactional
    public void delete(Long id) {
        perm.require("data:import:log:delete");
        SysImportLog e = repo.findById(id).orElseThrow(() -> new BizException(404, "导入记录不存在"));
        repo.delete(e);
    }

    /** 写一条导入结果。status: 0=成功 1=部分成功 2=失败 */
    @Transactional
    public SysImportLog record(SysImportLog e) {
        return repo.save(e);
    }

    /** 错误明细 → JSON 列；序列化失败降级成一行文本，绝不让它把整个导入结果搞丢 */
    public String encodeErrors(List<ImportErrorRow> errors) {
        if (errors == null || errors.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(errors);
        } catch (Exception ex) {
            log.warn("[import] failed to encode error detail: {}", ex.getMessage());
            return "[{\"reason\":\"错误明细序列化失败\"}]";
        }
    }

    private ImportLogDto toDto(SysImportLog e) {
        return new ImportLogDto(
                e.getId(), e.getDatasourceId(), e.getDatasourceName(), e.getDbType(),
                e.getTargetSchema(), e.getTargetTable(), e.getFileName(), e.getFileSize(),
                e.getSheetName(), e.getImportMode(),
                e.getTotalRows(), e.getInsertedRows(), e.getFailedRows(), e.getSkippedRows(),
                e.getStatus(), e.getErrorMessage(), decodeErrors(e.getErrorDetail()),
                e.getDurationMs(), e.getOperatorId(), e.getOperatorName(), e.getCreatedAt());
    }

    private List<ImportErrorRow> decodeErrors(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<List<ImportErrorRow>>() {});
        } catch (Exception ex) {
            List<ImportErrorRow> fallback = new ArrayList<>();
            fallback.add(new ImportErrorRow(0, "", "", "错误明细解析失败"));
            return fallback;
        }
    }
}
