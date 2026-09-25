package com.anomalyco.ifinbrain.web.datamgmt.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.datamgmt.dto.QueryParamSchema;
import com.anomalyco.ifinbrain.web.datamgmt.dto.QueryResultDto;
import com.anomalyco.ifinbrain.web.datamgmt.entity.SysDatasource;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 SQL 模板 + 参数 values → 真 JDBC 执行 → 出 QueryResultDto。
 *
 * 支持两种占位符语法（共存）：
 *   ① 经典 :name                  — 向前兼容（Phase 2 之前）
 *   ② Metabase 风格 {{name}}::type — ::type 是 PG/SQL 类型转换，cast 直接写到 SQL
 *
 * 安全：
 *   1. SqlSafetyChecker 先把关（禁词 + 单语句）
 *   2. 参数走 PreparedStatement，不做字符串拼接
 *   3. setQueryTimeout(N) — 跨 DB 通用
 *   4. setMaxRows(N) — 跨 DB 通用
 *
 * 提供两种执行模式：
 *   - execute(...)     全量收集到 List<List<Object>>，返回 QueryResultDto（在线预览）
 *   - iterateRows(...) 流式回调 RowHandler（CSV / XLSX 导出，无内存上限）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryExecutor {

    /** 行数上限；超过即截断并标 truncated=true */
    public static final int MAX_ROWS = 10_000;

    /** 超时秒数；setQueryTimeout 不支持部分驱动，但 PG/MySQL/MSSQL 都 OK */
    public static final int TIMEOUT_SECONDS = 30;

    /** {{name}}::type 形式取参数名；::type 是可选的 PG cast */
    private static final Pattern MUSTACHE_PATTERN = Pattern.compile(
            "\\{\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}\\}(?:::([a-zA-Z_][a-zA-Z0-9_]*))?");

    /** 纯 :name 形式（旧版，向后兼容）。(?<!) 排除前一个 : — 避免误吃 ::type 里的 :type */
    private static final Pattern COLON_PATTERN = Pattern.compile("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)");

    private final SqlSafetyChecker safety;
    private final DatasourceConnectionService connService;
    private final DatasourceService datasourceService;

    /**
     * 主入口（在线预览）：跑一条保存的查询，全量收集到 List。
     */
    public QueryResultDto execute(SysDatasource ds,
                                  String plainPassword,
                                  String sqlTemplate,
                                  List<QueryParamSchema> paramSchema,
                                  Map<String, String> paramValues) {

        // 1. 校验 + 必填
        safety.check(sqlTemplate);
        List<QueryParamSchema> schema = paramSchema == null ? List.of() : paramSchema;
        Map<String, String> values = paramValues == null ? Map.of() : paramValues;
        requireNonBlank(schema, values);

        // 2. 拿连接池 + 渲染 SQL
        HikariDataSource hikari = connService.getDataSource(ds.getId(), ds, plainPassword);
        SqlRendered rendered = renderSql(sqlTemplate, schema, values);

        // 3. 全量收集（RowHandler → List）
        CollectingHandler collector = new CollectingHandler();
        long start = System.currentTimeMillis();
        try {
            runWithBound(hikari, rendered, MAX_ROWS, collector);
        } catch (SQLException ex) {
            throw mapSqlException(ex);
        }
        long duration = System.currentTimeMillis() - start;

        log.info("[query] ds={} sql-len={} cols={} rows={} truncated={} durationMs={}",
                ds.getName(), sqlTemplate.length(),
                collector.cols.size(), collector.rows.size(), collector.truncated, duration);

        return QueryResultDto.builder()
                .columns(collector.cols)
                .rows(collector.rows)
                .rowCount(collector.rows.size())
                .truncated(collector.truncated)
                .durationMs(duration)
                .rowLimit(MAX_ROWS)
                .timeoutSec(TIMEOUT_SECONDS)
                .build();
    }

    /**
     * 流式执行：每行回调 RowHandler.onRow()。无内存上限（maxRows=0 表示不截断）。
     * 用于 CSV / XLSX 导出。
     */
    public IterateSummary iterateRows(SysDatasource ds,
                                      String plainPassword,
                                      String sqlTemplate,
                                      List<QueryParamSchema> paramSchema,
                                      Map<String, String> paramValues,
                                      long maxRows,
                                      RowHandler handler) throws Exception {

        // 1. 校验 + 必填
        safety.check(sqlTemplate);
        List<QueryParamSchema> schema = paramSchema == null ? List.of() : paramSchema;
        Map<String, String> values = paramValues == null ? Map.of() : paramValues;
        requireNonBlank(schema, values);

        // 2. 拿连接池 + 渲染 SQL
        HikariDataSource hikari = connService.getDataSource(ds.getId(), ds, plainPassword);
        SqlRendered rendered = renderSql(sqlTemplate, schema, values);

        long start = System.currentTimeMillis();
        CountingHandler counter = new CountingHandler(handler);
        try {
            runWithBound(hikari, rendered, (int) Math.min(maxRows > 0 ? maxRows : Integer.MAX_VALUE, Integer.MAX_VALUE), counter);
        } catch (SQLException ex) {
            throw mapSqlException(ex);
        }
        long duration = System.currentTimeMillis() - start;
        IterateSummary s = new IterateSummary(counter.rowCount(), counter.truncated, duration);
        try { handler.onComplete(s); } catch (Exception ignore) { /* consumer 失败不影响 summary */ }
        return s;
    }

    /**
     * 跑 SQL（共用路径）：取连接 → prepareStatement → setObject → 调 handler.onHeaders/onRow。
     */
    private void runWithBound(HikariDataSource hikari, SqlRendered rendered,
                              int maxRows, IteratingHandler h) throws SQLException {
        try (Connection conn = hikari.getConnection();
             PreparedStatement ps = conn.prepareStatement(rendered.jdbcSql)) {

            ps.setQueryTimeout(TIMEOUT_SECONDS);
            if (maxRows > 0 && maxRows < Integer.MAX_VALUE) ps.setMaxRows(maxRows);
            ps.setFetchSize(Math.min(maxRows > 0 ? maxRows : 1000, 1000));

            for (int i = 0; i < rendered.boundValues.size(); i++) {
                ps.setObject(i + 1, rendered.boundValues.get(i));
            }

            try (ResultSet rs = ps.executeQuery()) {
                List<QueryResultDto.Column> cols = buildColumns(rs.getMetaData());
                h.onHeaders(cols);

                int colCount = cols.size();
                while (rs.next()) {
                    if (maxRows > 0 && maxRows < Integer.MAX_VALUE
                            && h.rowCount() >= maxRows) {
                        h.markTruncated();
                        break;
                    }
                    List<Object> row = new ArrayList<>(colCount);
                    for (int i = 1; i <= colCount; i++) {
                        row.add(normalize(rs.getObject(i), cols.get(i - 1).getType()));
                    }
                    h.onRow(row);
                }
            }
        }
    }

    /**
     * 必填校验
     */
    private void requireNonBlank(List<QueryParamSchema> schema, Map<String, String> values) {
        for (QueryParamSchema p : schema) {
            String v = values.get(p.getName());
            if (Boolean.TRUE.equals(p.getRequired())
                    && (v == null || v.isBlank())) {
                throw new BizException(400, "参数缺失: " + p.getName());
            }
        }
    }

    private BizException mapSqlException(SQLException ex) {
        String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        log.warn("[query] failed: {}", msg);
        if (msg.toLowerCase().contains("timeout")
                || msg.toLowerCase().contains("interrupted")
                || msg.toLowerCase().contains("timed out")) {
            return new BizException(408, "查询超时（上限 " + TIMEOUT_SECONDS + "s）");
        }
        return new BizException(400, "SQL 执行失败: " + msg);
    }

    /**
     * 渲染 SQL：支持 :name 与 {{name}}::type 两种占位符。
     * 两遍：先把 {{name}}::type 规范成 :name（剥离 ::type），再统一把 :name 换 ? 并收集 bound。
     */
    SqlRendered renderSql(String template, List<QueryParamSchema> schema, Map<String, String> values) {
        Map<String, QueryParamSchema> schemaMap = new HashMap<>();
        for (QueryParamSchema p : schema) schemaMap.put(p.getName(), p);

        String normalized = MUSTACHE_PATTERN.matcher(template).replaceAll(m -> {
            String name = m.group(1);
            return ":" + name;
        });

        Matcher m = COLON_PATTERN.matcher(normalized);
        StringBuilder out = new StringBuilder();
        List<Object> bound = new ArrayList<>();
        int lastEnd = 0;
        while (m.find()) {
            out.append(normalized, lastEnd, m.start());
            String name = m.group(1);
            String raw  = values.get(name);
            QueryParamSchema meta = schemaMap.get(name);

            if (raw == null || raw.isBlank()) {
                out.append('?');
                bound.add(null);
            } else {
                Object converted = convertValue(raw, meta);
                out.append('?');
                bound.add(converted);
            }
            lastEnd = m.end();
        }
        out.append(normalized, lastEnd, normalized.length());
        return new SqlRendered(out.toString(), bound);
    }

    private Object convertValue(String raw, QueryParamSchema meta) {
        if (meta == null || meta.getType() == null || meta.getType().isBlank()) {
            return raw;
        }
        try {
            return switch (meta.getType().toLowerCase()) {
                case "number" -> new BigDecimal(raw.trim());
                case "date"   -> LocalDate.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
                case "datetime" -> LocalDateTime.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                case "string", "text" -> raw;
                default -> raw;
            };
        } catch (Exception e) {
            throw new BizException(400,
                    "参数 " + (meta == null ? "?" : meta.getName()) + " 无法解析为 " + meta.getType() + ": " + raw);
        }
    }

    /**
     * ResultSetMetaData → Column 列表（公共）
     */
    List<QueryResultDto.Column> buildColumns(ResultSetMetaData md) throws SQLException {
        int colCount = md.getColumnCount();
        List<QueryResultDto.Column> cols = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            String label = md.getColumnLabel(i);
            String name  = md.getColumnName(i);
            if (label == null || label.isEmpty()) label = name;
            cols.add(QueryResultDto.Column.builder()
                    .key(name)
                    .label(label)
                    .type(guessType(md.getColumnType(i), md.getColumnTypeName(i)))
                    .build());
        }
        return cols;
    }

    /**
     * 推测 JDBC type → 简单业务类型。供前端做"是日期就按日期展示"。
     */
    private String guessType(int jdbcType, String typeName) {
        return switch (jdbcType) {
            case Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.TINYINT,
                 Types.DECIMAL, Types.NUMERIC, Types.DOUBLE, Types.FLOAT,
                 Types.REAL -> "number";
            case Types.DATE         -> "date";
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "datetime";
            case Types.BOOLEAN, Types.BIT -> "boolean";
            default -> "string";
        };
    }

    /**
     * 把 DB 返回值规范化：时间/数字格式化为字符串。
     */
    private Object normalize(Object v, String type) {
        if (v == null) return null;
        try {
            return switch (type) {
                case "date" -> v instanceof java.sql.Date d
                        ? d.toLocalDate().toString()
                        : (v instanceof java.util.Date ud ? ud.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() : v.toString());
                case "datetime" -> v instanceof Timestamp ts
                        ? ts.toLocalDateTime().toString()
                        : (v instanceof java.util.Date ud ? ud.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString() : v.toString());
                case "number" -> v instanceof BigDecimal bd ? bd.toPlainString() : v.toString();
                default -> v.toString();
            };
        } catch (Exception e) {
            return v == null ? null : v.toString();
        }
    }

    /** renderSql 输出 */
    record SqlRendered(String jdbcSql, List<Object> boundValues) {}

    /** iterateRows 输出 */
    public record IterateSummary(long rowCount, boolean truncated, long durationMs) {}

    /** iterateRows 回调接口 */
    public interface RowHandler {
        void onHeaders(List<QueryResultDto.Column> columns);
        void onRow(List<Object> values);
        default void onComplete(IterateSummary summary) {}
    }

    /** 内部公共 handler 抽象（execute 与 iterateRows 共用 runWithBound） */
    private interface IteratingHandler {
        void onHeaders(List<QueryResultDto.Column> columns);
        void onRow(List<Object> row);
        void markTruncated();
        long rowCount();
    }

    /** execute 用的收集器 */
    private final class CollectingHandler implements IteratingHandler {
        List<QueryResultDto.Column> cols = List.of();
        final List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        long count = 0;
        public void onHeaders(List<QueryResultDto.Column> c) { this.cols = c; }
        public void onRow(List<Object> row) { rows.add(row); count++; }
        public void markTruncated() { this.truncated = true; }
        public long rowCount() { return count; }
    }

    /** iterateRows 用的转发器 */
    private static final class CountingHandler implements IteratingHandler {
        private final RowHandler delegate;
        long count = 0;
        boolean truncated = false;
        CountingHandler(RowHandler d) { this.delegate = d; }
        public void onHeaders(List<QueryResultDto.Column> c) { delegate.onHeaders(c); }
        public void onRow(List<Object> row) { delegate.onRow(row); count++; }
        public void markTruncated() { this.truncated = true; }
        public long rowCount() { return count; }
    }
}
