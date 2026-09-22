package com.anomalyco.fapiao.web.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "sys_setting")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SysSetting {

    @Id
    @Column(name = "key_name", length = 128)
    private String keyName;

    @Lob
    @Column(name = "value_text", columnDefinition = "TEXT")
    private String valueText;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}