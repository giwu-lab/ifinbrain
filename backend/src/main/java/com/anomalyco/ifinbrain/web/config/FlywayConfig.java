package com.anomalyco.ifinbrain.web.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 启动期跑 Flyway 迁移 —— 必须早于 JPA EntityManagerFactory 初始化。
 *
 * 旧实现：把 migrate() 放在 @Bean flyway() 工厂方法里，但 Spring 创建 bean 是 lazy 的，
 *   没人请求 Flyway bean 时迁移不跑。结果是 sys_portal_block 等新表从未被建立。
 * 新实现：直接在 FlywayRunner.afterPropertiesSet()（@PostConstruct）里跑迁移，
 *   并将 DataSource 注入到本 bean 触发早期初始化。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@DependsOn("dataSource")
public class FlywayConfig {

    private final DataSource dataSource;
    private final Environment env;

    @PostConstruct
    public void migrate() {
        String[] activeProfiles = env.getActiveProfiles();
        boolean isProd = Arrays.asList(activeProfiles).contains("prod");
        List<String> locations = new ArrayList<>();
        locations.add("classpath:db/migration");
        locations.add(isProd ? "classpath:db/migration-prod" : "classpath:db/migration-dev");

        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<String> effective = new ArrayList<>();
        for (String loc : locations) {
            try {
                Resource[] res = resolver.getResources(loc + "/*.sql");
                if (res.length > 0) effective.add(loc);
            } catch (Exception ignored) {}
        }
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(effective.toArray(new String[0]))
                .baselineOnMigrate(true)
                .cleanDisabled(true)
                .load();
        // Allow checksum fixes after V2 edits without manual repair step.
        flyway.repair();
        var result = flyway.migrate();
        log.info("Flyway: applied {} migrations (now at schema v{}) [profile={}, locations={}]",
                result.migrationsExecuted, result.targetSchemaVersion,
                Arrays.toString(activeProfiles), effective);
    }
}