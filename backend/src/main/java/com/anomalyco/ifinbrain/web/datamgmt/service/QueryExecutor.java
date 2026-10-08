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
 * 占位符<b>只支持一种</b>：Metabase 风格 {{name}}，可带 ::type cast。
 *   ::type 是 PG/SQL 类型转换写法，本项目在渲染期直接丢掉（类型以参数面板为准），
 *   保留它只是为了让用户从 Metabase/PCP 抄来的 SQL 能原样贴进来。
 * 已废弃的经典 :name 不再渲染，命中即抛 400 并提示改写成 {{name}}（见 LEGACY_COLON_PATTERN）。
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

    /** 分页取数的单页硬上限。防止有人传 pageSize=100000 把内存打回来。 */
    public static final int MAX_PAGE_SIZE = 2000;

    /** 分页取数允许的最大 offset。等价于「内存里最多可能攒多少行」的粗略闸门。 */
    public static final long MAX_OFFSET = 2_000_000L;

    /** 超时秒数；setQueryTimeout 不支持部分驱动，但 PG/MySQL/MSSQL 都 OK */
    public static final int TIMEOUT_SECONDS = 30;

    /** {{name}}::type 形式取参数名；::type 是可选的 PG cast */
    private static final Pattern MUSTACHE_PATTERN = Pattern.compile(
            "\\{\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}\\}(?:::([a-zA-Z_][a-zA-Z0-9_]*))?");

    /**
     * 已废弃的经典占位符 :name —— <b>只用于识别并报错，不再渲染</b>。
     *
     * <p>为什么不做「静默忽略」：取消支持后，如果有人（或历史模板）还留着 :name，
     * 它会作为字面量原样发给数据库，报出来的是
     * {@code syntax error at or near ":"} 这种天书错误，用户根本想不到是参数语法问题。
     * 显式拦下来告诉「请改成 {{name}}」才是负责任的做法。
     *
     * <p>(?&lt;!:) 排除前一个冒号 —— 否则 PG 的 {@code x::int} cast 会被误判成占位符。
     */
    private static final Pattern LEGACY_COLON_PATTERN = Pattern.compile("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)");

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
     * 分页执行（报表用）：只取 [offset, offset+limit) 这一段。
     *
     * <p>与 {@link #execute} 的区别是<b>内存有上界</b>：不管底表多大，
     * 本方法最多把 {@code limit} 行收进内存 —— 这是虚拟滚动表格能安全承载大数据量的前提。
     * execute() 一次收 MAX_ROWS(1万) 行，是「在线预览」语义，不是「报表」语义。
     *
     * <p>LIMIT/OFFSET 通过 {@link java.sql.Statement#setMaxRows} + 丢弃前 offset 行实现，
     * <b>不往 SQL 文本里拼任何东西</b>：不同数据库 LIMIT/OFFSET 语法不统一
     * （PG/MySQL `LIMIT n OFFSET m`，MSSQL 要 `OFFSET..FETCH`），拼字符串会同时踩
     * 注入与方言两个坑，且会破坏 SqlSafetyChecker 的「单语句」假设。
     *
     * <p>代价要说清楚：OFFSET 是**丢弃前 N 行**，不是数据库侧跳过，
     * 所以 offset 很大时依然要扫过那些行（只是不占 Java 堆内存）。
     * 这对「滚到第 500 页」是慢的，对「内存不爆」是有效的 ——
     * 真要随机跳几十万行，应该让 DBA 在 SQL 里写好 ORDER BY 与索引。
     */
    public PagedResult executePaged(SysDatasource ds,
                                    String plainPassword,
                                    String sqlTemplate,
                                    List<QueryParamSchema> paramSchema,
                                    Map<String, String> paramValues,
                                    long offset,
                                    int limit) {

        // 1. 校验 + 必填
        safety.check(sqlTemplate);
        List<QueryParamSchema> schema = paramSchema == null ? List.of() : paramSchema;
        Map<String, String> values = paramValues == null ? Map.of() : paramValues;
        requireNonBlank(schema, values);

        // 2. 分页参数归一化（越界直接夹到合法区间，不报错 —— 前端翻页边界抖动不该变成异常）
        int size = (int) Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        long off = Math.max(0, Math.min(offset, MAX_OFFSET));

        // 3. 拿连接池 + 渲染 SQL（与 execute 同一套 {{name}} / {{name}}::type 渲染）
        HikariDataSource hikari = connService.getDataSource(ds.getId(), ds, plainPassword);
        SqlRendered rendered = renderSql(sqlTemplate, schema, values);

        // 4. 多取 1 行用于判断 hasMore —— 这是「还有下一页」的唯一依据，
        //    比 COUNT(*) 便宜得多（COUNT 会让整个查询跑第二遍）。
        PagingHandler collector = new PagingHandler(off, size);
        long start = System.currentTimeMillis();
        try (Connection conn = hikari.getConnection();
             PreparedStatement ps = conn.prepareStatement(rendered.jdbcSql)) {

            ps.setQueryTimeout(TIMEOUT_SECONDS);
            ps.setFetchSize(Math.min(size + 1, 1000));

            for (int i = 0; i < rendered.boundValues.size(); i++) {
                ps.setObject(i + 1, rendered.boundValues.get(i));
            }

            try (ResultSet rs = ps.executeQuery()) {
                List<QueryResultDto.Column> cols = buildColumns(rs.getMetaData());
                collector.onHeaders(cols);

                int colCount = cols.size();
                while (rs.next()) {
                    if (collector.isFull()) break;
                    List<Object> row = new ArrayList<>(colCount);
                    for (int i = 1; i <= colCount; i++) {
                        row.add(normalize(rs.getObject(i), cols.get(i - 1).getType()));
                    }
                    collector.onRow(row);
                }
            }
        } catch (SQLException ex) {
            throw mapSqlException(ex);
        }
        long duration = System.currentTimeMillis() - start;

        log.info("[query:paged] ds={} offset={} size={} rows={} hasMore={} durationMs={}",
                ds.getName(), off, size, collector.rows.size(), collector.hasMore, duration);

        return new PagedResult(
                collector.cols,
                collector.rows,
                off,
                size,
                collector.hasMore,
                duration,
                TIMEOUT_SECONDS);
    }

    /**
     * 分页结果。
     *
     * <p>刻意<b>不含 total 总行数</b>：算总数必须再跑一次 {@code SELECT count(*)}，
     * 在大表上是把整个查询成本翻倍，而虚拟滚动只关心「还有没有下一页」。
     * 需要总数的场景（导出全量）走 {@link #iterateRows} 流式，不经过这里。
     */
    public record PagedResult(List<QueryResultDto.Column> columns,
                              List<List<Object>> rows,
                              long offset,
                              int size,
                              boolean hasMore,
                              long durationMs,
                              int timeoutSec) {}

    /**
     * executePaged 的收集器：跳过前 offset 行，最多保留 want 行，并用多读的那一行判定 hasMore。
     *
     * <p>关键：<b>want+1 那一行只用来判定，不进结果集</b>。否则前端每页都会多收一行，
     * 翻到第二页时第一页的末行与第二页的首行重复（边界错位一格）。
     * 非 static —— onRow 要调实例方法 normalize()。
     */
    private final class PagingHandler implements IteratingHandler {
        private final long skip;
        private final int want;
        List<QueryResultDto.Column> cols = List.of();
        final List<List<Object>> rows = new ArrayList<>();
        /** 读过的总行数（含被跳过的） */
        private long seen = 0;
        /** 是否确认还有下一页（读到第 want+1 行时置位） */
        boolean hasMore = false;

        PagingHandler(long offset, int size) {
            this.skip = offset;
            this.want = size;
        }

        public void onHeaders(List<QueryResultDto.Column> c) { this.cols = c; }

        public void onRow(List<Object> row) {
            seen++;
            if (seen <= skip) return;          // offset 之前的行：直接丢，不进内存
            if (rows.size() >= want) {
                // 第 want+1 行：证明后面还有数据，但不收进结果集
                hasMore = true;
                return;
            }
            rows.add(row);
        }

        /**
         * 是否可以停止读取。
         *
         * <p>判据是「offset 之后已读过的行数 &gt; want」，而不是「已收下的行数 &gt;= want」——
         * 后者会在 want 行刚收满时就 break，第 want+1 行永远读不到，hasMore 就永远是 false。
         * 差一行正是探针行的意义：读到它才知道后面还有数据。
         */
        public boolean isFull() { return (seen - skip) > want; }

        public void markTruncated() { /* 分页语义下不截断，由 offset 闸门兜底 */ }

        public long rowCount() { return rows.size(); }
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
     * 渲染 SQL：<b>只支持 {{name}} 一种占位符</b>（可带 ::type cast）。
     *
     * <p>两遍：先把 {{name}}::type 剥离成内部记号（保留类型名供 cast 用不到、直接丢掉），
     * 再统一换成 ? 并收集 bound 值。
     *
     * <p>渲染前先扫一遍已废弃的 :name —— 命中就抛 400 并点名让用户改成 {{name}}，
     * 而不是让它作为字面量漏给数据库换来一个 "syntax error at or near ':'"。
     */
    SqlRendered renderSql(String template, List<QueryParamSchema> schema, Map<String, String> values) {
        Map<String, QueryParamSchema> schemaMap = new HashMap<>();
        for (QueryParamSchema p : schema) schemaMap.put(p.getName(), p);

        rejectLegacyColonPlaceholders(template);

        // {{name}}::type → 内部记号 MARK+name+MARK；::type 本来就被后端忽略（类型以参数面板为准）
        String normalized = MUSTACHE_PATTERN.matcher(template).replaceAll(m -> MARK + m.group(1) + MARK);

        Matcher m = INTERNAL_MARK.matcher(normalized);
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

    /** 内部记号：用一个不会出现在 SQL 里的字符把占位名夹住，避免再引入第二套正则 */
    private static final String MARK = "";
    private static final Pattern INTERNAL_MARK = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)");

    /**
     * 拦下已废弃的 :name 占位符。
     *
     * <p>扫描前先<b>抹掉字符串字面量与注释</b>：否则
     * {@code where kind = 'type:amount'} 里的 {@code :amount} 会被误判成参数，
     * 把一条完全合法的查询拒掉。抹掉后剩下的 {@code :} 才是真的占位符。
     */
    private void rejectLegacyColonPlaceholders(String template) {
        if (template == null) return;
        String masked = maskLiterals(template);
        Matcher m = LEGACY_COLON_PATTERN.matcher(masked);
        List<String> hits = new ArrayList<>();
        while (m.find() && hits.size() < 5) hits.add(m.group(1));
        if (!hits.isEmpty()) {
            String list = String.join("、", hits);
            throw new BizException(400,
                    "参数引用语法已变更：不再支持 :name 形式（检测到 " + list + "）。"
                  + "请改用 {{name}}，例如 " + hits.get(0) + " → {{" + hits.get(0) + "}}");
        }
    }

    /**
     * 把字符串字面量 / 引号标识符 / 注释<b>整段内容</b>替换成等长空白。
     *
     * <p>整段抹掉是关键：只抹引号本身、留下 {@code 'type:amount'} 的内容是行不通的 ——
     * 内容里的 {@code :amount} 照样会被占位符正则命中，把一条完全合法的查询拒掉。
     *
     * <p>等长也是刻意的：保留原始下标与换行，报错信息里的列号仍然对得上原 SQL。
     * 支持 '…'（'' 转义）、"…"（"" 转义）、`…`、{@code -- } 行注释、{@code /* *}{@code /} 块注释。
     * 未闭合的引号按「一直吞到结尾」处理 —— 宁可漏判也不能把半个 SQL 当占位符。
     */
    private static String maskLiterals(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);

            if (c == '\'' || c == '"' || c == '`') {
                sb.append(' ');
                int j = i + 1;
                while (j < n) {
                    char d = sql.charAt(j);
                    if (d == c) {
                        // '' / "" 转义：两个引号都算内容，一起抹掉再继续
                        if (j + 1 < n && sql.charAt(j + 1) == c) {
                            sb.append("  ");
                            j += 2;
                            continue;
                        }
                        sb.append(' ');   // 收尾引号
                        j++;
                        break;
                    }
                    sb.append(d == '\n' ? '\n' : ' ');
                    j++;
                }
                i = j - 1;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') { sb.append(' '); i++; }
                i--;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                sb.append("  ");
                i += 2;
                while (i < n && !(sql.charAt(i) == '*' && i + 1 < n && sql.charAt(i + 1) == '/')) {
                    sb.append(sql.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) { sb.append("  "); i++; }
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
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
