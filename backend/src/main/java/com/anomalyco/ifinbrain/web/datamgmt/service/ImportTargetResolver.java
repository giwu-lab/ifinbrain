package com.anomalyco.ifinbrain.web.datamgmt.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.datamgmt.common.ColumnTypes;
import com.anomalyco.ifinbrain.web.datamgmt.common.ImportSafetyChecker;
import com.anomalyco.ifinbrain.web.datamgmt.dto.DatasourceSchemaDto;
import com.anomalyco.ifinbrain.web.datamgmt.entity.SysDatasource;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 导入目标解析：**执行期重新查一次 information_schema**，把表 / 列名收敛成白名单。
 *
 * <p>这是整个导入功能的信任根。前端传来的 schema / table / column 名字<b>全部</b>
 * 只当作「查询线索」，真正写进 SQL 的标识符一律用这里查回来的元数据原值。
 * 这样一次性解决了三件事：
 * <ol>
 *   <li><b>注入</b>：不在元数据里的名字根本走不到 SQL 文本拼接那一步；</li>
 *   <li><b>大小写折叠</b>：PG 未加引号建出来的 {@code Invoice} 实际叫 {@code invoice}，
 *       前端拿到的也是 {@code invoice}，但用户完全可能手输大写。
 *       用「不区分大小写查、用查回来的原值」兼容，而不是用用户输入去拼引号标识符 ——
 *       后者会拼出 {@code "Invoice"} 这个并不存在的表；</li>
 *   <li><b>表结构漂移</b>：用户预览时表还是 3 列，点导入时被人加了列 / 删了列，
 *       这里查到的就是当下的真实结构，不会拿着过期结构去写。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportTargetResolver {

    private final DatasourceService datasourceService;
    private final DatasourceConnectionService connService;
    private final ImportSafetyChecker safety;

    /** 一个已通过校验、可安全写入的目标表 */
    public record Target(SysDatasource ds,
                         String plainPassword,
                         String dbType,
                         String schema,
                         String table,
                         List<DatasourceSchemaDto.ColumnSchema> columns,
                         Map<String, DatasourceSchemaDto.ColumnSchema> byLowerName) {

        /** schema 限定的表名（已加方言引号） */
        public String qualifiedTable() {
            return safetyQuote(schema) + "." + safetyQuote(table);
        }

        private String safetyQuote(String id) {
            return switch (dbType == null ? "" : dbType.toLowerCase(Locale.ROOT)) {
                case "mysql" -> "`" + id + "`";
                case "mssql" -> "[" + id + "]";
                default      -> "\"" + id + "\"";
            };
        }

        public String qualifiedColumn(DatasourceSchemaDto.ColumnSchema col) {
            return safetyQuote(col.getName());
        }

        /** 可映射的列（排除自增 / 生成列） */
        public List<DatasourceSchemaDto.ColumnSchema> writableColumns() {
            return columns.stream().filter(c -> !Boolean.TRUE.equals(c.getGenerated())).toList();
        }
    }

    /**
     * 解析目标表。
     *
     * @param permCode 调用方要求的权限位（{@code data:import:view} 或 {@code data:import:execute}）——
     *                 解密密码 = 建立到外部库的连接，必须按调用场景分别授权
     */
    public Target resolve(Long datasourceId, String schema, String table, String permCode) {
        if (datasourceId == null) throw new BizException(400, "请选择数据源");

        // 三层校验：字符集 → 系统 schema 黑名单 → 长度。都在拼 SQL 之前发生。
        String safeSchema = safety.checkSchema(schema, null);
        String safeTable = safety.checkTable(table);

        DatasourceService.DecryptedDatasource dec =
                datasourceService.loadDecrypted(datasourceId, permCode);
        SysDatasource ds = dec.entity();
        String plain = dec.plainPassword();

        List<DatasourceSchemaDto.ColumnSchema> cols = loadColumns(ds, plain, safeSchema, safeTable);
        if (cols.isEmpty()) {
            throw new BizException(400, "目标表不存在或当前账号看不到它: "
                    + safeSchema + "." + safeTable
                    + "（请确认表名拼写，以及数据源账号是否有该表的权限）");
        }

        Map<String, DatasourceSchemaDto.ColumnSchema> byLower = new LinkedHashMap<>();
        for (var c : cols) {
            byLower.put(c.getName().toLowerCase(Locale.ROOT), c);
        }
        return new Target(ds, plain, ds.getDbType(), safeSchema, safeTable, cols, byLower);
    }

    /**
     * 按列名（不区分大小写）取元数据列；取不到抛 400 —— 这就是白名单的落地点。
     */
    public DatasourceSchemaDto.ColumnSchema requireColumn(Target target, String columnName) {
        String checked = safety.checkColumn(columnName);
        var col = target.byLowerName().get(checked.toLowerCase(Locale.ROOT));
        if (col == null) {
            throw new BizException(400, "列 \"" + checked + "\" 不存在于目标表 "
                    + target.schema() + "." + target.table());
        }
        return col;
    }

    /** 三种库统一的 information_schema 查询（列顺序与 buildColumn 一一对应） */
    private List<DatasourceSchemaDto.ColumnSchema> loadColumns(SysDatasource ds, String plain,
                                                              String schema, String table) {
        String sql = switch (ds.getDbType().toLowerCase(Locale.ROOT)) {
            case "mysql" ->
                    "SELECT column_name, data_type, is_nullable, character_maximum_length, "
                            + "numeric_precision, numeric_scale, extra FROM information_schema.columns "
                            + "WHERE LOWER(table_schema) = LOWER(?) AND LOWER(table_name) = LOWER(?) "
                            + "ORDER BY ordinal_position";
            case "mssql" ->
                    "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, "
                            + "NUMERIC_PRECISION, NUMERIC_SCALE, IS_IDENTITY FROM INFORMATION_SCHEMA.COLUMNS "
                            + "WHERE LOWER(TABLE_SCHEMA) = LOWER(?) AND LOWER(TABLE_NAME) = LOWER(?) "
                            + "ORDER BY ORDINAL_POSITION";
            default ->
                    "SELECT column_name, data_type, is_nullable, character_maximum_length, "
                            + "numeric_precision, numeric_scale, is_identity, is_generated FROM information_schema.columns "
                            + "WHERE LOWER(table_schema) = LOWER(?) AND LOWER(table_name) = LOWER(?) "
                            + "ORDER BY ordinal_position";
        };

        List<DatasourceSchemaDto.ColumnSchema> cols = new ArrayList<>();
        HikariDataSource hikari = connService.buildOnce(ds, plain);
        try (Connection conn = hikari.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(buildColumn(rs, ds.getDbType()));
                }
            }
        } catch (BizException ex) {
            throw ex;
        } catch (Exception e) {
            log.warn("[import] load columns failed ds={} table={}.{}: {}",
                    ds.getName(), schema, table, e.getMessage());
            throw new BizException(400, "读取目标表结构失败: " + e.getMessage());
        } finally {
            try { hikari.close(); } catch (Exception ignored) {}
        }
        return cols;
    }

    private DatasourceSchemaDto.ColumnSchema buildColumn(ResultSet rs, String dbType) throws Exception {
        boolean generated;
        if ("mysql".equalsIgnoreCase(dbType)) {
            String extra = rs.getString(7);
            String e = extra == null ? "" : extra.toUpperCase(Locale.ROOT);
            generated = e.contains("AUTO_INCREMENT") || e.contains("GENERATED");
        } else if ("mssql".equalsIgnoreCase(dbType)) {
            generated = "YES".equalsIgnoreCase(rs.getString(7));
        } else {
            generated = "YES".equalsIgnoreCase(rs.getString(7))
                    || "ALWAYS".equalsIgnoreCase(rs.getString(8));
        }

        return DatasourceSchemaDto.ColumnSchema.builder()
                .name(rs.getString(1))
                .dataType(rs.getString(2))
                .type(ColumnTypes.guess(rs.getString(2)))
                .nullable("YES".equalsIgnoreCase(rs.getString(3)))
                .maxLength(intOrNull(rs, 4))
                .precision(intOrNull(rs, 5))
                .scale(intOrNull(rs, 6))
                .generated(generated)
                .build();
    }

    /** SQL NULL 一律 null（驱动 getInt 会给 0，必须 wasNull 判断） */
    private Integer intOrNull(ResultSet rs, int idx) throws Exception {
        int v = rs.getInt(idx);
        return rs.wasNull() ? null : v;
    }

    /**
     * 构造 INSERT 语句。列名来自元数据原值，值位恒为 {@code ?}。
     * 没有任何用户输入被拼进这段文本 —— 这是本功能唯一允许拼接的地方。
     */
    public String buildInsertSql(Target target, List<DatasourceSchemaDto.ColumnSchema> insertCols) {
        if (insertCols.isEmpty()) {
            throw new BizException(400, "没有选择任何要写入的字段");
        }
        StringBuilder cols = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < insertCols.size(); i++) {
            if (i > 0) {
                cols.append(", ");
                placeholders.append(", ");
            }
            cols.append(target.qualifiedColumn(insertCols.get(i)));
            placeholders.append('?');
        }
        return "INSERT INTO " + target.qualifiedTable()
                + " (" + cols + ") VALUES (" + placeholders + ")";
    }

    /** 预检展示用：目标表当前行数 */
    public long countRows(Target target) {
        HikariDataSource hikari = connService.buildOnce(target.ds(), target.plainPassword());
        try (Connection conn = hikari.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + target.qualifiedTable())) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception e) {
            log.warn("[import] count rows failed table={}.{}: {}",
                    target.schema(), target.table(), e.getMessage());
            return -1L;   // -1 = 未知（某些账号无 SELECT 权限），前端显示「—」
        } finally {
            try { hikari.close(); } catch (Exception ignored) {}
        }
    }
}