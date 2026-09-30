package com.anomalyco.ifinbrain.web.todo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "sys_todo_tag",
       uniqueConstraints = @UniqueConstraint(name = "uk_todo_tag_owner_name", columnNames = {"owner_id", "name"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TodoTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
