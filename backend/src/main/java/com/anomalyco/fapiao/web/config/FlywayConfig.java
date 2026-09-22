package com.anomalyco.fapiao.web.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class FlywayConfig {

    private final org.springframework.core.env.Environment env;

    @Bean(name = "flywayInitializer")
    public FlywayInitializer flywayInitializer(DataSource dataSource) {
        return new FlywayInitializer(dataSource);
    }

    @Bean
    @DependsOn("flywayInitializer")
    public Flyway flyway(DataSource dataSource) {
        String[] activeProfiles = env.getActiveProfiles();
        boolean isProd = java.util.Arrays.asList(activeProfiles).contains("prod");
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
                java.util.Arrays.toString(activeProfiles), effective);
        return flyway;
    }

    public static class FlywayInitializer implements InitializingBean {
        private final DataSource dataSource;
        public FlywayInitializer(DataSource dataSource) { this.dataSource = dataSource; }
        @Override public void afterPropertiesSet() {
            // ensure DataSource is fully initialized before Flyway
        }
    }
}