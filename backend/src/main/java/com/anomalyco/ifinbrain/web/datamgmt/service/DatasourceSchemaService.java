package com.anomalyco.ifinbrain.web.datamgmt.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.datamgmt.common.ColumnTypes;
import com.anomalyco.ifinbrain.web.datamgmt.dto.DatasourceSchemaDto;
import com.anomalyco.ifinbrain.web.datamgmt.entity.SysDatasource;
import com.anomalyco.ifinbrain.web.security.CryptoUtil;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 数据源元数据浏览（schema / tables / columns）。
 * 复用 DatasourceConnectionService，但不走缓存——一次性建池连完即关。
 *
 * <p>类型推断与「自增列」判定抽到了 common/ColumnTypes，供数据导入模块
 * （ImportTargetResolver，执行期重查元数据）复用同一口径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceSchemaService {

    private final DatasourceConnectionService connService;
    private final DatasourceService datasourceService;
    private final CryptoUtil crypto;
    private final PermissionChecker perm;

    @Transactional(readOnly = true)
    public DatasourceSchemaDto fetch(Long id) {
        // 权限：只看元数据，**不该**要求 data:datasource:test。
        // 历史问题：这里原先走 datasourceService.loadDecrypted(id)，而它内部
        // perm.require("data:datasource:test") —— 按 V10 的分配，viewer 只有 view，
        // 于是「浏览表结构」对 viewer 直接 403。改用 loadDecrypted(id, permCode) 重载，
        // 让「看元数据」与「测连通」两个权限位彻底分开。
        perm.require("data:datasource:view");

        // 复用 datasourceService.loadDecrypted（它会解密 + 探测）
        DatasourceService.DecryptedDatasource dec =
                datasourceService.loadDecrypted(id, "data:datasource:view");
        SysDatasource ds = dec.entity();
        String plain = dec.plainPassword();

        HikariDataSource hikari = connService.buildOnce(ds, plain);
        try (Connection conn = hikari.getConnection()) {
            return switch (ds.getDbType().toLowerCase(Locale.ROOT)) {
                case "postgresql" -> fetchPg(conn, ds);
                case "mysql"      -> fetchMysql(conn, ds);
                case "mssql"      -> fetchMssql(conn, ds);
                default -> throw new BizException(400, "不支持的 dbType: " + ds.getDbType());
            };
        } catch (BizException ex) {
            throw ex;
        } catch (Exception e) {
            log.warn("[schema] ds={} failed: {}", ds.getName(), e.getMessage());
            throw new BizException(400, "读取 schema 失败: " + e.getMessage());
        } finally {
            try { hikari.close(); } catch (Exception ignored) {}
        }
    }

    private DatasourceSchemaDto fetchPg(Connection conn, SysDatasource ds) throws Exception {
        String defaultSchema = "public";
        List<DatasourceSchemaDto.TableSchema> tables = new ArrayList<>();

        // PG: pg_tables 含 schemaname + tablename
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT schemaname, tablename FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema') ORDER BY schemaname, tablename")) {
            while (rs.next()) {
                String schema = rs.getString(1);
                String table = rs.getString(2);
                tables.add(loadColumnsPg(conn, schema, table));
            }
        }

        return DatasourceSchemaDto.builder()
                .datasourceId(ds.getId())
                .dbType(ds.getDbType())
                .defaultSchema(defaultSchema)
                .tables(tables)
                .build();
    }

    private DatasourceSchemaDto.TableSchema loadColumnsPg(Connection conn, String schema, String table) throws Exception {
        List<DatasourceSchemaDto.ColumnSchema> cols = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT column_name, data_type, is_nullable, character_maximum_length, "
                        + "numeric_precision, numeric_scale, is_identity, is_generated FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(DatasourceSchemaDto.ColumnSchema.builder()
                            .name(rs.getString(1))
                            .dataType(rs.getString(2))
                            .type(ColumnTypes.guess(rs.getString(2)))
                            .nullable("YES".equalsIgnoreCase(rs.getString(3)))
                            .maxLength(getInt(rs, 4))
                            .precision(getInt(rs, 5))
                            .scale(getInt(rs, 6))
                            // serial / GENERATED ALWAYS AS IDENTITY / 生成列
                            .generated("YES".equalsIgnoreCase(rs.getString(7))
                                    || "ALWAYS".equalsIgnoreCase(rs.getString(8)))
                            .build());
                }
            }
        }
        return DatasourceSchemaDto.TableSchema.builder()
                .name(schema + "." + table)
                .schema(schema)
                .simpleName(table)
                .columns(cols)
                .build();
    }

    private DatasourceSchemaDto fetchMysql(Connection conn, SysDatasource ds) throws Exception {
        // 库名就是 ds.databaseName
        String db = ds.getDatabaseName();
        List<DatasourceSchemaDto.TableSchema> tables = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT table_name, table_schema FROM information_schema.tables "
                        + "WHERE table_schema = ? ORDER BY table_name")) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tname = rs.getString(1);
                    String sname = rs.getString(2);
                    tables.add(loadColumnsMysql(conn, sname, tname));
                }
            }
        }
        return DatasourceSchemaDto.builder()
                .datasourceId(ds.getId())
                .dbType(ds.getDbType())
                .defaultSchema(db)
                .tables(tables)
                .build();
    }

    private DatasourceSchemaDto.TableSchema loadColumnsMysql(Connection conn, String schema, String table) throws Exception {
        List<DatasourceSchemaDto.ColumnSchema> cols = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT column_name, data_type, is_nullable, character_maximum_length, "
                        + "numeric_precision, numeric_scale, extra FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(DatasourceSchemaDto.ColumnSchema.builder()
                            .name(rs.getString(1))
                            .dataType(rs.getString(2))
                            .type(ColumnTypes.guess(rs.getString(2)))
                            .nullable("YES".equalsIgnoreCase(rs.getString(3)))
                            .maxLength(getInt(rs, 4))
                            .precision(getInt(rs, 5))
                            .scale(getInt(rs, 6))
                            .generated(isMysqlGenerated(rs.getString(7)))
                            .build());
                }
            }
        }
        return DatasourceSchemaDto.TableSchema.builder()
                .name(schema + "." + table)
                .schema(schema)
                .simpleName(table)
                .columns(cols)
                .build();
    }

    private DatasourceSchemaDto fetchMssql(Connection conn, SysDatasource ds) throws Exception {
        String defaultSchema = "dbo";
        List<DatasourceSchemaDto.TableSchema> tables = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT TABLE_SCHEMA, TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                             + "WHERE TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_SCHEMA, TABLE_NAME")) {
            while (rs.next()) {
                String sname = rs.getString(1);
                String tname = rs.getString(2);
                tables.add(loadColumnsMssql(conn, sname, tname));
            }
        }
        return DatasourceSchemaDto.builder()
                .datasourceId(ds.getId())
                .dbType(ds.getDbType())
                .defaultSchema(defaultSchema)
                .tables(tables)
                .build();
    }

    private DatasourceSchemaDto.TableSchema loadColumnsMssql(Connection conn, String schema, String table) throws Exception {
        List<DatasourceSchemaDto.ColumnSchema> cols = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, "
                        + "NUMERIC_PRECISION, NUMERIC_SCALE, IS_IDENTITY FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(DatasourceSchemaDto.ColumnSchema.builder()
                            .name(rs.getString(1))
                            .dataType(rs.getString(2))
                            .type(ColumnTypes.guess(rs.getString(2)))
                            .nullable("YES".equalsIgnoreCase(rs.getString(3)))
                            .maxLength(getInt(rs, 4))
                            .precision(getInt(rs, 5))
                            .scale(getInt(rs, 6))
                            .generated("YES".equalsIgnoreCase(rs.getString(7)))
                            .build());
                }
            }
        }
        return DatasourceSchemaDto.TableSchema.builder()
                .name(schema + "." + table)
                .schema(schema)
                .simpleName(table)
                .columns(cols)
                .build();
    }

    /**
     * 读一列 int 值；SQL NULL / 类型不符一律给 null 而不是抛异常。
     *
     * character_maximum_length / numeric_precision 在 information_schema 里是 nullable 的
     * （非字符串列前者为 NULL），驱动对 NULL 的行为也不统一（getInt 会给 0），
     * 所以必须先 wasNull() 判断 —— 否则 varchar 列会拿到 maxLength=0，
     * 导入预检就会把所有字符串都误判成「超长」。
     */
    private Integer getInt(ResultSet rs, int idx) {
        try {
            int v = rs.getInt(idx);
            return rs.wasNull() ? null : v;
        } catch (SQLException e) {
            return null;
        }
    }

    /** MySQL 的 EXTRA 列：auto_increment / VIRTUAL GENERATED / STORED GENERATED */
    private boolean isMysqlGenerated(String extra) {
        if (extra == null) return false;
        String e = extra.toUpperCase(Locale.ROOT);
        return e.contains("AUTO_INCREMENT") || e.contains("GENERATED");
    }
}