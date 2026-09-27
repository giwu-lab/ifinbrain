package com.anomalyco.ifinbrain.web.datamgmt.service;

import com.anomalyco.ifinbrain.web.common.BizException;
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
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 数据源元数据浏览（schema / tables / columns）。
 * 复用 DatasourceConnectionService，但不走缓存——一次性建池连完即关。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceSchemaService {

    private static final Set<String> NUMERIC_TYPES = Set.of(
            "int", "int2", "int4", "int8", "smallint", "integer", "bigint",
                    "tinyint", "mediumint",
                    "decimal", "numeric", "real", "double", "float",
                    "money", "smallmoney");
    private static final Set<String> DATETIME_TYPES = Set.of(
            "date", "time", "datetime", "timestamp", "timestamptz",
                    "smalldatetime", "datetime2", "datetimeoffset");
    private static final Set<String> BOOL_TYPES = Set.of("bool", "boolean", "bit");

    private final DatasourceConnectionService connService;
    private final DatasourceService datasourceService;
    private final CryptoUtil crypto;
    private final PermissionChecker perm;

    @Transactional(readOnly = true)
    public DatasourceSchemaDto fetch(Long id) {
        perm.require("data:datasource:view");

        // 直接复用 datasourceService.loadDecrypted（它会解密 + 探测）
        DatasourceService.DecryptedDatasource dec = datasourceService.loadDecrypted(id);
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
                "SELECT column_name, data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(DatasourceSchemaDto.ColumnSchema.builder()
                            .name(rs.getString(1))
                            .dataType(rs.getString(2))
                            .type(guessType(rs.getString(2)))
                            .nullable("YES".equalsIgnoreCase(rs.getString(3)))
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
                "SELECT column_name, data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(DatasourceSchemaDto.ColumnSchema.builder()
                            .name(rs.getString(1))
                            .dataType(rs.getString(2))
                            .type(guessType(rs.getString(2)))
                            .nullable("YES".equalsIgnoreCase(rs.getString(3)))
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
                "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(DatasourceSchemaDto.ColumnSchema.builder()
                            .name(rs.getString(1))
                            .dataType(rs.getString(2))
                            .type(guessType(rs.getString(2)))
                            .nullable("YES".equalsIgnoreCase(rs.getString(3)))
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

    private String guessType(String dataType) {
        if (dataType == null) return "unknown";
        String t = dataType.toLowerCase(Locale.ROOT);
        // 去掉括号参数 (varchar(64) → varchar)
        int paren = t.indexOf('(');
        if (paren > 0) t = t.substring(0, paren).trim();
        if (NUMERIC_TYPES.contains(t)) return "number";
        if (DATETIME_TYPES.contains(t)) return "date".equals(t) ? "date" : "datetime";
        if (BOOL_TYPES.contains(t)) return "boolean";
        return "string";
    }
}