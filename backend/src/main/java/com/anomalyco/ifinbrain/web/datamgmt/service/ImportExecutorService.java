package com.anomalyco.ifinbrain.web.datamgmt.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.datamgmt.dto.*;
import com.anomalyco.ifinbrain.web.datamgmt.entity.SysImportLog;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 导入执行：预检（dry-run）与写入（commit）。
 *
 * <p><b>两层错误语义，界线必须清楚</b>：
 * <ul>
 *   <li><b>单元格级错误</b>（日期写成 2024-13-45、数字写成「待定」、超长被拒）：
 *       在类型转换阶段就拦下，该行<b>跳过</b>，其余行照常入库。结果是「部分成功」。</li>
 *   <li><b>数据库级错误</b>（唯一键冲突、非空约束、外键失败、连接中断）：
 *       批处理直接抛异常 → <b>整批回滚</b>，status=2。
 *       这类错误没法「跳过一行继续」—— 要做到就得把整批拆成逐行往返，
 *       5 万行会慢到不可用；与其做出「导了一半」的脏状态，不如明确失败让人重来。</li>
 * </ul>
 *
 * <p><b>事务</b>：单事务包住整批，每 {@code batchSize} 行 {@code executeBatch()} 一次。
 * 「导了一半」比「导失败」难查得多 —— 前者要人工比对差集，后者看一眼日志就知道重来即可。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportExecutorService {

    private final ImportSessionService sessions;
    private final ImportTargetResolver resolver;
    private final ImportTypeConverter converter;
    private final ImportLogService logService;
    private final DatasourceConnectionService connService;
    private final PermissionChecker perm;

    @Value("${ifinbrain.import.batch-size:1000}")
    private int defaultBatchSize;

    @Value("${ifinbrain.import.query-timeout-sec:120}")
    private int queryTimeoutSec;

    @Value("${ifinbrain.import.max-error-rows:200}")
    private int maxErrorRows;

    // ==================== 预检 ====================

    /**
     * 只解析目标列元数据，不建映射、不碰会话。
     * preview 接口用它把「执行时会写入的那张表当时的真实列」直接回给前端，
     * 用户在映射界面看到的选项不是一份可能过期的快照。
     */
    public List<DatasourceSchemaDto.ColumnSchema> resolveTargetColumns(
            Long datasourceId, String schema, String table, String permCode) {
        return resolver.resolve(datasourceId, schema, table, permCode).columns();
    }

    /** dry-run：不写库，只回答「如果现在执行会怎样」 */
    public ImportValidateDto validate(ImportMappingReq req) {
        perm.require("data:import:view");
        ImportSessionService.Session session = sessions.require(req.sessionId());
        Plan plan = buildPlan(session, req, "data:import:view");

        Scan scan = scan(session, plan);
        long currentRows = resolver.countRows(plan.target());

        return new ImportValidateDto(
                session.getId(),
                scan.total,
                scan.good,
                scan.badRowCount,
                scan.warningCount,
                scan.errors,
                currentRows,
                scan.good > 0);
    }

    // ==================== 执行 ====================

    public ImportCommitResultDto commit(ImportMappingReq req) {
        perm.require("data:import:execute");
        ImportSessionService.Session session = sessions.require(req.sessionId());
        Plan plan = buildPlan(session, req, "data:import:execute");

        // 先扫一遍拿到计数与错误明细：转换层的问题在这一步已经全部可见，
        // 不要写到一半才发现「全是坏行」。同一份转换是确定性的，commit 时再算一次结果一致。
        Scan scan = scan(session, plan);
        if (scan.good == 0) {
            log.warn("[import] nothing to insert: session={} total={} badRows={}",
                    session.getId(), scan.total, scan.badRowCount);
            throw new BizException(400, "没有任何一行可以导入：共 " + scan.total
                    + " 行，其中 " + scan.badRowCount + " 行存在字段错误。请修正后重试。");
        }

        long start = System.currentTimeMillis();
        int inserted;
        try {
            inserted = writeRows(session, plan);
        } catch (SQLException ex) {
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            log.warn("[import] commit failed table={}.{} : {}",
                    plan.target().schema(), plan.target().table(), msg);
            recordFailure(session, plan, scan, msg, System.currentTimeMillis() - start);
            throw new BizException(400, "导入失败已回滚（未写入任何数据）：" + msg);
        } catch (RuntimeException ex) {
            recordFailure(session, plan, scan, String.valueOf(ex.getMessage()),
                    System.currentTimeMillis() - start);
            throw ex;
        }
        long duration = System.currentTimeMillis() - start;

        int skipped = scan.total - inserted;
        int status = skipped > 0 ? 1 : 0;
        String message = skipped > 0
                ? "导入完成，跳过 " + skipped + " 行（字段转换失败，详见错误明细）"
                : "导入完成";

        SysImportLog logRow = recordSuccess(session, plan, scan, inserted, skipped, status, message, duration);
        log.info("[import] committed session={} table={}.{} total={} inserted={} skipped={} durationMs={}",
                session.getId(), plan.target().schema(), plan.target().table(),
                scan.total, inserted, skipped, duration);

        // 会话已消费：留着既占内存，用户误点第二次还会二次写入
        sessions.discard(session.getId());

        return new ImportCommitResultDto(
                logRow.getId(), scan.total, inserted, skipped, scan.warningCount,
                duration, status, message, scan.errors);
    }

    /** 单事务批量写入；SQLException 向上抛，由 commit 统一翻译 + 记失败日志 */
    private int writeRows(ImportSessionService.Session session, Plan plan) throws SQLException {
        int inserted = 0;
        HikariDataSource hikari = connService.getDataSource(
                plan.target().ds().getId(), plan.target().ds(), plan.target().plainPassword());

        try (Connection conn = hikari.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(plan.sql())) {
                ps.setQueryTimeout(queryTimeoutSec);

                int pending = 0;
                for (int i = 0; i < session.getRows().size(); i++) {
                    List<String> row = session.getRows().get(i);
                    if (plan.options().skipEmptyRow() && isBlankRow(row)) continue;

                    // errorSink 传 null：明细已经在 scan 里收过一次，这里不重复收集
                    RowConv conv = convertRow(row, plan, 0, null);
                    if (!conv.ok()) continue;

                    for (int c = 0; c < conv.values().length; c++) {
                        ps.setObject(c + 1, conv.values()[c]);
                    }
                    ps.addBatch();
                    pending++;
                    if (pending >= plan.options().batchSize()) {
                        inserted += countUpdates(ps.executeBatch());
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    inserted += countUpdates(ps.executeBatch());
                }
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                safeRollback(conn);
                throw ex;
            } finally {
                // 归还连接前恢复原状：别把外部库的连接留在「关掉了自动提交」的状态
                try { conn.setAutoCommit(autoCommit); } catch (SQLException ignore) {}
            }
        }
        return inserted;
    }

    // ==================== 计划 ====================

    /** 一次导入的完整执行计划：目标表 + 写入列 + 源列下标 + 行为选项 + INSERT 语句 */
    private record Plan(ImportTargetResolver.Target target,
                        List<DatasourceSchemaDto.ColumnSchema> insertCols,
                        int[] srcIndex,
                        String sql,
                        Options options) {}

    private record Options(boolean emptyAsNull,
                           boolean truncateString,
                           boolean skipEmptyRow,
                           int batchSize) {}

    private Plan buildPlan(ImportSessionService.Session session,
                           ImportMappingReq req,
                           String permCode) {

        if (req == null || req.mappings() == null || req.mappings().isEmpty()) {
            throw new BizException(400, "请至少映射一个字段");
        }

        // 执行期重查元数据：不在白名单里的名字走不到 SQL 拼接那一步
        ImportTargetResolver.Target target =
                resolver.resolve(session.getDatasourceId(), session.getSchema(), session.getTable(), permCode);

        ImportMappingReq.ImportOptions o = req.options() == null
                ? new ImportMappingReq.ImportOptions(null, null, null, null, null)
                : req.options();

        String mode = (o.mode() == null || o.mode().isBlank()) ? "append" : o.mode();
        if (!"append".equals(mode)) {
            // truncate 会清空整张业务表，upsert 需要唯一键元数据 —— 两者都还没开放。
            // 显式拒绝而不是悄悄按 append 处理：静默降级会让用户以为数据被清了。
            throw new BizException(400, "导入模式 \"" + mode + "\" 尚未开放，当前仅支持追加导入（append）");
        }

        List<DatasourceSchemaDto.ColumnSchema> insertCols = new ArrayList<>();
        List<Integer> srcIdx = new ArrayList<>();
        Set<String> usedTargets = new LinkedHashSet<>();

        for (ImportMappingReq.ImportMapping m : req.mappings()) {
            if (m == null || Boolean.FALSE.equals(m.enabled())) continue;
            if (m.sourceHeader() == null || m.targetColumn() == null) continue;

            var col = resolver.requireColumn(target, m.targetColumn());
            if (Boolean.TRUE.equals(col.getGenerated())) {
                throw new BizException(400, "列 \"" + col.getName() + "\" 是自增/生成列，不能导入数据");
            }
            if (!usedTargets.add(col.getName())) {
                throw new BizException(400, "目标列 \"" + col.getName() + "\" 被映射了多次，请只保留一个");
            }

            int idx = indexOfHeader(session.getHeaders(), m.sourceHeader());
            if (idx < 0) {
                throw new BizException(400, "源文件里找不到表头 \"" + m.sourceHeader() + "\"，请重新选择文件");
            }
            insertCols.add(col);
            srcIdx.add(idx);
        }

        if (insertCols.isEmpty()) {
            throw new BizException(400, "请至少选择一个要导入的字段");
        }

        int[] srcIndex = new int[srcIdx.size()];
        for (int i = 0; i < srcIndex.length; i++) srcIndex[i] = srcIdx.get(i);

        int batch = (o.batchSize() == null) ? defaultBatchSize : o.batchSize();
        batch = Math.min(Math.max(batch, 200), 2000);

        Options options = new Options(
                o.emptyAsNull() == null || o.emptyAsNull(),
                o.truncateString() == null || o.truncateString(),
                o.skipEmptyRow() == null || o.skipEmptyRow(),
                batch);

        return new Plan(target, insertCols, srcIndex,
                resolver.buildInsertSql(target, insertCols), options);
    }

    private int indexOfHeader(List<String> headers, String header) {
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).equals(header)) return i;
        }
        return -1;
    }

    // ==================== 扫描与转换 ====================

    /** dry-run 的统计结果 */
    private static final class Scan {
        int total;
        int good;
        int badRowCount;
        int warningCount;
        final List<ImportErrorRow> errors = new ArrayList<>();
    }

    /** 单行转换结果 */
    private record RowConv(Object[] values, boolean ok, int warnings) {}

    private Scan scan(ImportSessionService.Session session, Plan plan) {
        Scan s = new Scan();
        for (int i = 0; i < session.getRows().size(); i++) {
            List<String> row = session.getRows().get(i);
            s.total++;
            if (plan.options().skipEmptyRow() && isBlankRow(row)) continue;

            RowConv conv = convertRow(row, plan, session.physicalRowNo(i), s);
            s.warningCount += conv.warnings();
            if (conv.ok()) {
                s.good++;
            } else {
                s.badRowCount++;
            }
            // 明细只留前 maxErrorRows 条：5000 行 × 3 列全错 = 1.5 万条，
            // 全塞回前端会让表格卡死；超出部分只保留计数。
            if (s.errors.size() > maxErrorRows) {
                s.errors.subList(maxErrorRows, s.errors.size()).clear();
            }
        }
        return s;
    }

    /**
     * 转一行。
     *
     * @param rowNo     源文件里的物理行号（1 基）—— 错误提示必须和用户在 Excel 里看到的对得上
     * @param errorSink 明细收集器；传 null 表示只判成败不记明细（commit 的写入阶段用）
     */
    private RowConv convertRow(List<String> row, Plan plan, int rowNo, Scan errorSink) {
        Object[] values = new Object[plan.insertCols().size()];
        boolean bad = false;
        int warnings = 0;

        for (int c = 0; c < plan.insertCols().size(); c++) {
            var col = plan.insertCols().get(c);
            int src = plan.srcIndex()[c];
            String raw = (src >= 0 && src < row.size()) ? row.get(src) : null;

            var cv = converter.convert(raw, col,
                    plan.options().emptyAsNull(), plan.options().truncateString());

            if (!cv.isOk()) {
                bad = true;
                if (errorSink != null && errorSink.errors.size() < maxErrorRows) {
                    errorSink.errors.add(new ImportErrorRow(
                            rowNo, col.getName(), ImportErrorRow.clip(raw), cv.error()));
                }
            } else {
                values[c] = cv.value();
                if (cv.truncated()) warnings++;
            }
        }
        return new RowConv(values, !bad, warnings);
    }

    // ==================== 写入辅助 ====================

    /**
     * executeBatch 的返回值在不同驱动上语义不同：
     * PG / MSSQL 返回每批的更新行数（可直接求和）；
     * MySQL 驱动可能返回 {@code SUCCESS_NO_INFO}(-2)。
     * 所以只在拿到明确正数时才累加，拿不到就按 SUCCESS_NO_INFO 的条数兜底。
     */
    private int countUpdates(int[] updateCounts) {
        int sum = 0;
        for (int c : updateCounts) {
            if (c > 0) sum += c;
        }
        if (sum == 0) {
            for (int c : updateCounts) {
                if (c == Statement.SUCCESS_NO_INFO) sum++;
            }
        }
        return sum;
    }

    private void safeRollback(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignore) {
            // 回滚失败说明连接已经断了 —— 外层会报出原始异常，这里不掩盖
        }
    }

    private boolean isBlankRow(List<String> row) {
        for (String c : row) {
            if (c != null && !c.isBlank()) return false;
        }
        return true;
    }

    // ==================== 日志 ====================

    private SysImportLog recordSuccess(ImportSessionService.Session session, Plan plan, Scan scan,
                                       int inserted, int skipped, int status,
                                       String message, long durationMs) {
        SysImportLog e = baseLog(session, plan);
        e.setInsertedRows(inserted);
        e.setSkippedRows(skipped);
        e.setStatus((short) status);
        e.setErrorMessage(truncate(message, 1000));
        e.setErrorDetail(logService.encodeErrors(scan.errors));
        e.setDurationMs(durationMs);
        return logService.record(e);
    }

    private void recordFailure(ImportSessionService.Session session, Plan plan,
                               Scan scan, String message, long durationMs) {
        try {
            SysImportLog e = baseLog(session, plan);
            e.setInsertedRows(0);
            e.setSkippedRows(scan.total);
            e.setStatus((short) 2);
            e.setErrorMessage(truncate(message, 1000));
            e.setErrorDetail(logService.encodeErrors(scan.errors));
            e.setDurationMs(durationMs);
            logService.record(e);
        } catch (RuntimeException ex) {
            // 记录失败本身不能掩盖真正的导入错误
            log.warn("[import] failed to write import log: {}", ex.getMessage());
        }
    }

    private SysImportLog baseLog(ImportSessionService.Session session, Plan plan) {
        return SysImportLog.builder()
                .datasourceId(plan.target().ds().getId())
                .datasourceName(plan.target().ds().getName())
                .dbType(plan.target().dbType())
                .targetSchema(plan.target().schema())
                .targetTable(plan.target().table())
                .fileName(session.getFileName())
                .fileSize(session.getFileSize())
                .sheetName(session.getSheetName())
                .importMode("append")
                .totalRows(session.getRows().size())
                .operatorId(perm.currentUserId())
                .operatorName(perm.currentUsername())
                .build();
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}