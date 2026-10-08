package com.anomalyco.ifinbrain.web.documents.note.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 笔记的点名共享名单（一篇笔记 × 一个用户 × 一个授权等级）。
 *
 * <p>与 {@code Note.scopeType} 的分工：scope 表达「整组可见」，本表表达「具体到人的读写授权」。
 * 两者可并存 —— 例如 scope=TEAM（同部门都能看）同时把某个人单独提到 WRITE。
 */
@Entity
@Table(name = "sys_note_share")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NoteShare {

    public static final String GRANT_READ  = "READ";
    public static final String GRANT_WRITE = "WRITE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK → sys_note(id) ON DELETE CASCADE：笔记没了名单自动没。 */
    @Column(name = "note_id", nullable = false)
    private Long noteId;

    /**
     * sys_user.id。**故意不建外键**：人被删后笔记不该跟着消失，
     * 历史内容保留；列表侧用 LEFT JOIN 容忍悬空 id（与 sys_note.owner_id 同取舍）。
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** READ = 只读；WRITE = 可编辑。 */
    @Column(name = "grant_type", nullable = false, length = 8)
    private String grantType;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (this.grantType == null || this.grantType.isBlank()) this.grantType = GRANT_READ;
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
    }
}