package com.anomalyco.ifinbrain.web.datamgmt.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "sys_datasource")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SysDatasource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 显示名，例如"财务 ERP"，唯一 */
    @Column(nullable = false, length = 64, unique = true)
    private String name;

    /** 'mysql' | 'mssql' | 'postgresql' */
    @Column(name = "db_type", nullable = false, length = 16)
    private String dbType;

    @Column(nullable = false, length = 128)
    private String host;

    @Column(nullable = false)
    private Integer port;

    @Column(name = "database_name", nullable = false, length = 128)
    private String databaseName;

    @Column(nullable = false, length = 64)
    private String username;

    /** CryptoUtil.encrypt(plain) 后的密文，前端永远拿不到明文 */
    @Column(name = "password_enc", nullable = false, columnDefinition = "TEXT")
    private String passwordEnc;

    @Column(length = 255)
    private String remark;

    /** 0=禁用 / 1=启用 */
    @Column(nullable = false)
    private Short status;

    /** 最近一次测试：0=fail / 1=ok / NULL=未测 */
    @Column(name = "last_test_ok")
    private Short lastTestOk;

    @Column(name = "last_test_at")
    private LocalDateTime lastTestAt;

    @Column(name = "last_test_msg", length = 255)
    private String lastTestMsg;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) this.status = 1;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}