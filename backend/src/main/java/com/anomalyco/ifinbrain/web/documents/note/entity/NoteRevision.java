package com.anomalyco.ifinbrain.web.documents.note.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 笔记版本快照 —— 每次保存写入一条<b>保存前</b>的内容。
 *
 * <p>为什么要快照而不是只留最后一次：团队笔记里最贵的错误是「A 写了两小时，B 一键保存覆盖掉」。
 * 有了历史，回滚就是一次普通保存（不删历史），冲突发生时也能看到「上一版长什么样」。
 *
 * <p>保留策略：最近 50 条/篇（服务层按 revision desc 裁剪），防止长笔记无限膨胀。
 */
@Entity
@Table(name = "sys_note_revision")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NoteRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK → sys_note(id) ON DELETE CASCADE。 */
    @Column(name = "note_id", nullable = false)
    private Long noteId;

    /** 从 1 递增，与笔记自身的 version 对齐（version=N 表示已保存过 N 次）。 */
    @Column(nullable = false)
    private Integer revision;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "editor_id")
    private Long editorId;

    @Column(name = "editor_name", length = 64)
    private String editorName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
    }
}