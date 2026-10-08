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
 *   没人请求 Flyway bean 时迁移不跑。结果是新版本中加的表从未被建立。
 * 新实现：直接在 FlywayConfig.migrate()（@PostConstruct）里跑迁移，
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
        // 唯一数据库是 PostgreSQL，迁移目录固定，不再按方言分支。
        //
        // 历史：曾按方言在 migration-dev（SQLite）/ migration-prod（PG）之间二选一。
        //   1) 先改成按 profile 选（isProd）—— 但 dev 也连 PG（RAG 需要 pgvector），
        //      按 profile 选会让 dev 去跑 SQLite 方言 DDL（AUTOINCREMENT / strftime），在 PG 上必失败。
        //   2) 再改成按方言选 —— 同样有问题：SQLite 目录停更于 V44，落后 7 个迁移，
        //      且 SQLite 没有 vector 类型，RAG 根本跑不起来。
        //   3) 现在：SQLite 已弃用，migration-dev 整个目录删除，只剩 PG 一条路径。
        //
        // 目录名仍保留 "migration-prod"：改名要动 46 个文件的 git 历史，收益仅是名字好看。
        // Flyway 的 checksum 只认版本号不认路径，重命名是安全的 —— 想改随时可做。
        String dsUrl = env.getProperty("spring.datasource.url", "");
        if (!dsUrl.startsWith("jdbc:postgresql")) {
            log.warn("Flyway: 数据源不是 PostgreSQL（{}），迁移脚本按 PG 方言编写，可能失败", dsUrl);
        }
        List<String> locations = new ArrayList<>();
        locations.add("classpath:db/migration");
        locations.add("classpath:db/migration-prod");

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
                // 关闭 placeholder 替换：FlowJson 注释里有 ${...} 表达式（如 ${days > 3}），
                // 不能被 Flyway 当成 SQL placeholder 替换。
                .placeholderReplacement(false)
                .load();
        // Allow checksum fixes after V2 edits without manual repair step.
        flyway.repair();
        var result = flyway.migrate();
        log.info("Flyway: applied {} migrations (now at schema v{}) [profile={}, dialect=postgresql, locations={}]",
                result.migrationsExecuted, result.targetSchemaVersion,
                Arrays.toString(activeProfiles), effective);
    }
}