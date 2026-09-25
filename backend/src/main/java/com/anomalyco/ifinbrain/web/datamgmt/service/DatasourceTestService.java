package com.anomalyco.ifinbrain.web.datamgmt.service;

import com.anomalyco.ifinbrain.web.datamgmt.dto.TestResultDto;
import com.anomalyco.ifinbrain.web.datamgmt.entity.SysDatasource;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 测试连接服务。
 *
 * 两个入口：
 * <ul>
 *   <li>{@link #testExisting(Long)} — 测已存数据源（解密 → 测 → 关池 → 写回 lastTestOk / lastTestAt / lastTestMsg）</li>
 *   <li>{@link #testNewConfig(SysDatasource, String)} — 测表单输入（创建/编辑时实时验证；不写库）</li>
 * </ul>
 *
 * 两个入口都不入连接池缓存（测试结束即关池），
 * 让缓存里的池只在真正被 SQL 执行器用到时才存活。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceTestService {

    private final DatasourceService datasourceService;
    private final DatasourceConnectionService connectionService;

    /**
     * 测已存数据源。需要 data:datasource:test。
     */
    public TestResultDto testExisting(Long id) {
        DatasourceService.DecryptedDatasource d = datasourceService.loadDecrypted(id);
        TestResultDto result = testNewConnection(d.entity(), d.plainPassword());
        String summary = result.isOk() ? shortVersion(result) : truncate(result.getMessage());
        datasourceService.recordTestResult(id, result.isOk(), summary);
        return result;
    }

    /**
     * 测表单里的配置。需要 data:datasource:update。
     */
    public TestResultDto testNewConfig(SysDatasource e, String plainPassword) {
        datasourceService.requireUpdatePerm();
        return testNewConnection(e, plainPassword);
    }

    // ===================== 核心 =====================

    private TestResultDto testNewConnection(SysDatasource e, String plainPassword) {
        long t0 = System.currentTimeMillis();
        HikariDataSource ds;
        try {
            ds = connectionService.buildOnce(e, plainPassword);
        } catch (Exception ex) {
            // HikariDataSource 构造本身会校验驱动/URL/账号；这里捕获以保持返回 ok=false 的契约
            return TestResultDto.builder()
                    .ok(false)
                    .latencyMs(System.currentTimeMillis() - t0)
                    .message(humanize(ex))
                    .build();
        }
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {

            st.setQueryTimeout(5);
            st.execute("SELECT 1");                       // 连通性

            String version = "";
            try (ResultSet rs = st.executeQuery(versionSql(e.getDbType()))) {
                if (rs.next()) version = rs.getString(1);
            } catch (Exception ex) {
                log.debug("version probe failed for {}: {}", e.getDbType(), ex.getMessage());
            }

            long latency = System.currentTimeMillis() - t0;
            return TestResultDto.builder()
                    .ok(true)
                    .version(version)
                    .latencyMs(latency)
                    .message(String.format("%s://%s:%d/%s",
                            e.getDbType(), e.getHost(), e.getPort(), e.getDatabaseName()))
                    .build();

        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - t0;
            log.warn("Datasource test failed [{}://{}:{}]: {}", e.getDbType(), e.getHost(), e.getPort(), ex.getMessage());
            return TestResultDto.builder()
                    .ok(false)
                    .latencyMs(latency)
                    .message(humanize(ex))
                    .build();
        } finally {
            try { ds.close(); } catch (Exception ignored) {}
        }
    }

    /** 三种 DB 取版本号的 SQL */
    private static String versionSql(String dbType) {
        return switch (dbType) {
            case "mysql"      -> "SELECT VERSION()";
            case "postgresql" -> "SELECT version()";
            case "mssql"      -> "SELECT @@VERSION";
            default -> "SELECT 1";
        };
    }

    private static String shortVersion(TestResultDto r) {
        String v = r.getVersion() == null ? "" : r.getVersion();
        if (v.length() > 24) v = v.substring(0, 24) + "…";
        return v;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 250 ? s.substring(0, 250) : s;
    }

    /** 给前端"失败"行内展示：剥 stack trace，只留根因首行 */
    private static String humanize(Exception ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = root.getMessage();
        if (msg == null) msg = ex.getClass().getSimpleName();
        int newline = msg.indexOf('\n');
        if (newline > 0) msg = msg.substring(0, newline);
        return msg.length() > 80 ? msg.substring(0, 80) + "…" : msg;
    }
}