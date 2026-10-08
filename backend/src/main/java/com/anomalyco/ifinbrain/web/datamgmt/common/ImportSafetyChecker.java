package com.anomalyco.ifinbrain.web.datamgmt.common;

import com.anomalyco.ifinbrain.web.common.BizException;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 数据导入的**标识符**安全校验器。
 *
 * <p><b>为什么单独一个类，不复用 {@code SqlSafetyChecker}</b>：
 * SqlSafetyChecker 的存在意义是「自定义查询链路只允许 SELECT」。它是那条只读通道的
 * 安全网，一旦为了导入去加「允许 INSERT」的白名单开关，整条防线就形同虚设
 * （SELECT 和 INSERT 走同一个入口，任何人都能构造出绕过检查的语句）。
 * 导入是另一条链路、另一套权限、另一种风险模型，所以另起炉灶，职责单一：
 * <b>只管标识符（库 / 表 / 列名），不碰值、不解析 SQL 语法。</b>
 *
 * <p><b>注入面分析</b>：导入语句形如
 * <pre>{@code INSERT INTO "public"."invoice" ("no","amount") VALUES (?,?)}</pre>
 * 值全部走 {@code PreparedStatement} 绑定 —— <b>值没有注入面</b>。
 * 唯一无法参数化、必须拼进 SQL 文本的就是标识符。所以本类的四层防线就是全部的防线：
 * <ol>
 *   <li><b>字符集白名单</b>：只允许 {@code [A-Za-z_][A-Za-z0-9_$-]{0,62}}。
 *       真实世界里 99.9% 的表名列名都满足（PG 允许 $，MySQL 允许 $ 与 -）。
 *       引号、空格、分号、括号、注释符全部无法通过 —— 所以引号内**不需要**再转义：
 *       能进到引号里的字符集与引号本身不重叠。</li>
 *   <li><b>元数据比对</b>：{@link com.anomalyco.ifinbrain.web.datamgmt.service.ImportTargetResolver}
 *       在**执行期**重新查 information_schema，只接受与真实元数据完全一致的标识符。</li>
 *   <li><b>系统 schema 黑名单</b>：{@code pg_catalog} / {@code information_schema} /
 *       {@code sys} / {@code mysql} 等一律拒绝 —— 即使它真的出现在元数据查询结果里。</li>
 *   <li><b>方言引号</b>：PG {@code "x"}、MySQL {@code `x`}、MSSQL {@code [x]}，
 *       保留字表名（如 {@code order}）才不会炸。</li>
 * </ol>
 */
@Component
public class ImportSafetyChecker {

    /**
     * 标识符字符集：首字符必须是字母或下划线（数字开头的合法标识符极罕见，
     * 直接拒掉比加特例省事），后续允许数字 / {@code _} / {@code $} / {@code -}。
     *
     * <p>刻意<b>不</b>允许中文与空格：真实的库表列名几乎不会是中文，
     * 而放开字符集会连 quotes 一起放进白名单。
     * 代价是「列名是中文表」这种极端场景导不进去 —— 需要时再单独开一条受控白名单。
     */
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$-]{0,62}$");

    /**
     * 系统 / 保留 schema 黑名单（小写比较）。
     *
     * <p>为什么要显式黑名单而不是只信 information_schema 过滤：
     * PG 的 information_schema 只读元数据，写进去不会破坏系统，但 MSSQL / MySQL 的
     * {@code sys} 系列、MySQL 的 {@code mysql} 库写坏了会让外部库自身故障。
     * 导入是业务功能，不该有能力搞坏数据源。
     */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            // 标准 SQL
            "information_schema",
            // PostgreSQL
            "pg_catalog", "pg_toast", "pg_temp_1", "pg_toast_temp_1",
            // MySQL
            "mysql", "performance_schema", "sys",
            // SQL Server
            "db_owner", "db_accessadmin", "db_securityadmin", "guest");

    /** 校验并返回一个可安全拼接的 schema 名 */
    public String checkSchema(String schema, String dbType) {
        String s = checkIdentifier(schema, "schema");
        String lower = s.toLowerCase(Locale.ROOT);
        // 前缀匹配覆盖 pg_temp_2 / pg_toast_temp_2 这类带序号的临时 schema
        boolean isSystem = SYSTEM_SCHEMAS.contains(lower)
                || lower.startsWith("pg_temp")
                || lower.startsWith("pg_toast")
                || lower.startsWith("sys");
        if (isSystem) {
            throw new BizException(400, "禁止写入系统 schema: " + s);
        }
        return s;
    }

    /** 校验并返回一个可安全拼接的表名 */
    public String checkTable(String table) {
        return checkIdentifier(table, "表名");
    }

    /** 校验并返回一个可安全拼接的列名 */
    public String checkColumn(String column) {
        return checkIdentifier(column, "列名");
    }

    /**
     * 字符集白名单。
     *
     * <p>报错信息里回显用户输入：这些值来自用户自己在页面上选的表/列，
     * 回显帮助定位「到底哪个名字不合法」；且已通过前端下拉选择，不构成反射攻击面。
     */
    private String checkIdentifier(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(400, label + "不能为空");
        }
        String s = raw.trim();
        if (!IDENTIFIER.matcher(s).matches()) {
            throw new BizException(400,
                    label + " \"" + s + "\" 非法：只允许字母、数字、下划线、$、-（首字符须为字母或下划线，长度 ≤ 63）");
        }
        return s;
    }

    /** 按方言加引号。已通过字符集白名单，引号本身不可能出现在标识符里，无需再转义。 */
    public String quote(String identifier, String dbType) {
        return switch (dbType == null ? "" : dbType.toLowerCase(Locale.ROOT)) {
            case "mysql"      -> "`" + identifier + "`";
            case "mssql"      -> "[" + identifier + "]";
            default           -> "\"" + identifier + "\"";   // postgresql 及其它
        };
    }

    /** schema + 表名（PG / MySQL / MSSQL 都支持 schema 限定，MSSQL 语法是 [schema].[table]） */
    public String quoteTable(String schema, String table, String dbType) {
        return quote(schema, dbType) + "." + quote(table, dbType);
    }
}