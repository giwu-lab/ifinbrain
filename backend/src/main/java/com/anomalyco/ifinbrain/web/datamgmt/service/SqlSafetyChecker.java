package com.anomalyco.ifinbrain.web.datamgmt.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 安全检查器。目标：拒绝任何非 SELECT 的写操作 / DDL / 管理语句。
 *
 * 限制（不引入 SQL parser 的前提下能做到的）：
 * <ul>
 *   <li>拒绝包含禁词的 SQL（覆盖 UPDATE/INSERT/DELETE/REPLACE/DROP/ALTER/TRUNCATE/CREATE/GRANT/REVOKE/MERGE/EXEC/HANDLER/SHOW/LOAD DATA/INTO OUTFILE/CALL/SET/COMMIT 等）</li>
 *   <li>拒绝多语句（除注释外不允许出现第二个 ';')</li>
 *   <li>不解析字符串字面量里的禁词（极小概率的误判用 biz 提示给用户自检）</li>
 * </ul>
 *
 * 注意：仍要做 PreparedStatement 参数绑定（不让 :param 走字符串拼接），见 QueryExecutor。
 */
@Component
public class SqlSafetyChecker {

    /**
     * 禁词；大小写不敏感；词边界 \b 避免误伤 column 名包含 delete 之类。
     *
     * 覆盖：
     *  - 写：DML（INSERT/UPDATE/DELETE/REPLACE/MERGE）+ 锁/事务（LOCK/SET/COMMIT/ROLLBACK/BEGIN/START）
     *  - DDL/对象管理（CREATE/DROP/ALTER/TRUNCATE/RENAME/GRANT/REVOKE/COPY）
     *  - 存储/执行（EXEC/EXECUTE/CALL/HANDLER）
     *  - 元数据泄漏（SHOW/DESCRIBE/EXPLAIN/USE）
     *  - 文件 I/O（LOAD/OUTFILE/DUMPFILE）
     *  - PG 特有（VACUUM/REINDEX/ANALYZE/CLUSTER）
     */
    private static final Set<String> FORBIDDEN = new LinkedHashSet<>(java.util.List.of(
            // DML 写
            "INSERT", "UPDATE", "DELETE", "REPLACE", "MERGE", "UPSERT",
            // 事务 / 锁
            "SET", "COMMIT", "ROLLBACK", "BEGIN", "START", "LOCK", "UNLOCK",
            // DDL / 对象管理
            "DROP", "ALTER", "TRUNCATE", "CREATE", "RENAME", "GRANT", "REVOKE",
            "COPY",
            // 存储 / 执行
            "EXEC", "EXECUTE", "CALL", "HANDLER",
            // 元数据泄漏
            "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "USE",
            // 文件 I/O（MySQL: LOAD DATA INFILE / SELECT ... INTO OUTFILE / DUMPFILE）
            "LOAD", "OUTFILE", "DUMPFILE",
            // PG 特有
            "VACUUM", "REINDEX", "ANALYZE", "CLUSTER",
            // SQLite 特有
            "ATTACH", "DETACH", "PRAGMA"
    ));

    private static final Pattern KEYWORD_PATTERN;
    static {
        String joined = String.join("|", FORBIDDEN);
        // \b 在 SQL 标识符 / 空格 / 行首 / 标点 处都能断
        KEYWORD_PATTERN = Pattern.compile(
                "\\b(" + joined + ")\\b",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** 多语句检测：忽略首尾空白和 ; 之间的纯注释/空白 */
    private static final Pattern SEMICOLON_PATTERN = Pattern.compile(";");

    /** SQL 注释：-- 到行尾 / /* ... *&#47; */
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /**
     * 检查 SQL；发现危险词抛 BizException(400)。
     */
    public void check(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new BizException(400, "SQL 不能为空");
        }

        // 先剥注释（避免注释里写 DROP 触发误报；用户也基本不会把执行语句塞进注释）
        String stripped = stripComments(sql);

        // 禁词扫描
        Matcher m = KEYWORD_PATTERN.matcher(stripped);
        if (m.find()) {
            throw new BizException(400,
                    "SQL 含禁止关键字: " + m.group(1).toUpperCase()
                            + "。自定义查询只允许 SELECT（含 WITH ... SELECT）。");
        }

        // 多语句检测：去掉首尾分号后，仍有 ';' 视为多语句
        String trimmed = stripped.trim();
        while (trimmed.endsWith(";")) trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        if (SEMICOLON_PATTERN.matcher(trimmed).find()) {
            throw new BizException(400, "SQL 含多条语句，仅允许单条 SELECT。");
        }
    }

    private String stripComments(String sql) {
        String s = LINE_COMMENT.matcher(sql).replaceAll(" ");
        s = BLOCK_COMMENT.matcher(s).replaceAll(" ");
        return s;
    }
}