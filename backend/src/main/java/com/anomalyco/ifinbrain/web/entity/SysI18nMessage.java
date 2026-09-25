package com.anomalyco.ifinbrain.web.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "sys_i18n_message",
       uniqueConstraints = @UniqueConstraint(name = "uq_i18n_ns_code", columnNames = {"namespace", "code"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SysI18nMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String namespace;

    @Column(nullable = false, length = 128)
    private String code;

    @Column(name = "value_json", nullable = false, columnDefinition = "TEXT")
    private String valueJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}