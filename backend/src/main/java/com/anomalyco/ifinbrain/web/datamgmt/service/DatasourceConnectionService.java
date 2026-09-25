package com.anomalyco.ifinbrain.web.datamgmt.service;

import com.anomalyco.ifinbrain.web.datamgmt.entity.SysDatasource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据源连接池服务。
 *
 * 设计选择（相对 dynamic-datasource / AbstractRoutingDataSource）：
 * <ul>
 *   <li>不替换 Spring Boot 默认 DataSource（本地 sys_* / invoice 等仍走 JPA）</li>
 *   <li>DB 是 source of truth — 改了 sys_datasource 行后，下一次 getDataSource(id) 自动按新配置重建连接池</li>
 *   <li>ConcurrentHashMap 做软缓存；update/delete 时显式 evict</li>
 *   <li>池参数偏保守：max=4, minIdle=0, idleTimeout=60s, maxLife=10min — 外部库连接不预热、不长留</li>
 * </ul>
 *
 * 阶段 2 的 SQL 执行器（未实现）将直接调 getDataSource(id) 拿连接。
 */
@Slf4j
@Service
public class DatasourceConnectionService {

    /** id → HikariDataSource */
    private final ConcurrentHashMap<Long, HikariDataSource> cache = new ConcurrentHashMap<>();

    /**
     * 取连接池；缓存未命中则按 cfg 重建
     */
    public HikariDataSource getDataSource(Long id, SysDatasource cfg, String plainPassword) {
        return cache.computeIfAbsent(id, k -> build(cfg, plainPassword));
    }

    /**
     * 配置变更后调用 — 关掉旧池、移除缓存
     */
    public void evict(Long id) {
        HikariDataSource old = cache.remove(id);
        if (old != null) {
            try {
                old.close();
                log.info("Evicted datasource pool id={}", id);
            } catch (Exception e) {
                log.warn("Failed to close pool for datasource id={}: {}", id, e.getMessage());
            }
        }
    }

    /**
     * 清空所有缓存（极端情况：master key 轮换后）
     */
    public void evictAll() {
        cache.forEach((id, ds) -> {
            try { ds.close(); } catch (Exception ignored) {}
        });
        cache.clear();
        log.info("Evicted ALL datasource pools");
    }

    public int cacheSize() {
        return cache.size();
    }

    /**
     * 公开给 TestService 用：一次性建池 + 关池，**不**入缓存
     */
    public HikariDataSource buildOnce(SysDatasource cfg, String plainPassword) {
        return build(cfg, plainPassword);
    }

    private HikariDataSource build(SysDatasource cfg, String plainPassword) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(jdbcUrl(cfg));
        hc.setUsername(cfg.getUsername());
        hc.setPassword(plainPassword);
        hc.setMaximumPoolSize(4);
        hc.setMinimumIdle(0);
        hc.setConnectionTimeout(5_000);
        hc.setValidationTimeout(3_000);
        hc.setIdleTimeout(60_000);
        hc.setMaxLifetime(10 * 60_000);
        hc.setPoolName("ds-" + cfg.getDbType() + "-" + cfg.getId());
        // 启动期探测驱动缺失只记日志，不阻断其他 DB 测试
        return new HikariDataSource(hc);
    }

    /**
     * 三种 DB 类型 URL 生成；trustStore / SSL 等高级参数留待阶段 2
     */
    public static String jdbcUrl(SysDatasource cfg) {
        return switch (cfg.getDbType()) {
            case "mysql"      -> String.format("jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                                              cfg.getHost(), cfg.getPort(), cfg.getDatabaseName());
            case "postgresql" -> String.format("jdbc:postgresql://%s:%d/%s",
                                              cfg.getHost(), cfg.getPort(), cfg.getDatabaseName());
            case "mssql"      -> String.format("jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=true;trustServerCertificate=true",
                                              cfg.getHost(), cfg.getPort(), cfg.getDatabaseName());
            default -> throw new IllegalArgumentException("Unsupported dbType: " + cfg.getDbType());
        };
    }
}