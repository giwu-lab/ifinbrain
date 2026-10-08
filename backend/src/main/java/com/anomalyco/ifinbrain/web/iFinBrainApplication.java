package com.anomalyco.ifinbrain.web;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.ZoneId;
import java.util.TimeZone;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableJpaAuditing
@EnableJpaRepositories(basePackages = {
    "com.anomalyco.ifinbrain.web.repository",
    "com.anomalyco.ifinbrain.web.datamgmt.repository",
    "com.anomalyco.ifinbrain.web.bi.repository",
    "com.anomalyco.ifinbrain.web.report.repository",
    "com.anomalyco.ifinbrain.web.documents.repository",
    "com.anomalyco.ifinbrain.web.documents.note.repository",
    "com.anomalyco.ifinbrain.web.design.form.repository",
    "com.anomalyco.ifinbrain.web.design.flow.repository",
    "com.anomalyco.ifinbrain.web.preference.repository",
    "com.anomalyco.ifinbrain.web.storage.repository",
    "com.anomalyco.ifinbrain.web.todo.repository"
})
@ConfigurationPropertiesScan(basePackages = "com.anomalyco.ifinbrain.web.config")
public class iFinBrainApplication {

    /** 项目默认时区：Asia/Shanghai (UTC+8)。保证不依赖宿主机时区设置。 */
    public static final String DEFAULT_ZONE_ID = "Asia/Shanghai";

    public static void main(String[] args) {
        // 在 Spring 启动前设置 JVM 默认时区，避免 Hibernate / Jackson / 日志 等任何依赖
        // TimeZone.getDefault() 的组件出现不一致。
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(DEFAULT_ZONE_ID)));
        SpringApplication.run(iFinBrainApplication.class, args);
    }

    @PostConstruct
    void logTimezone() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        System.out.println("[ifinbrain] default JVM timezone = "
                + TimeZone.getDefault().getID()
                + " | current time = " + now
                + " | system zone = " + ZoneId.systemDefault().getId());
    }
}