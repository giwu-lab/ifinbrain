package com.anomalyco.ifinbrain.web.documents.note.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 笔记（Markdown）。
 *
 * <p>为什么笔记是独立实体而不是 {@code sys_document} 的一行：
 * 文件的授权单位是「档案库」，笔记的授权单位是「人 / 部门 / 名单」；
 * 文件的正文是不可编辑的字节流，笔记的正文是可反复编辑的 markdown 文本。
 * 两者共用一套权限码会把「谁能看这个库」和「谁能改这篇笔记」搅在一起。
 *
 * <p>软删除未启用（AGENTS §4.1），「归档」用 {@code status=0} 表达，不删行。
 */
@Entity
@Table(name = "sys_note")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Note {

    public static final String SCOPE_PERSONAL = "PERSONAL";
    public static final String SCOPE_TEAM    = "TEAM";
    public static final String SCOPE_LIBRARY = "LIBRARY";
    public static final String SCOPE_ORG     = "ORG";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    /** Markdown 源码。后端**不存渲染后的 HTML** —— 渲染在前端做，过 DOMPurify。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "scope_type", nullable = false, length = 16)
    private String scopeType;

    /** scopeType = TEAM 时必填。 */
    @Column(name = "scope_dept_id")
    private Long scopeDeptId;

    /** scopeType = LIBRARY 时必填；FK → sys_document_category(id) ON DELETE CASCADE。 */
    @Column(name = "category_id")
    private Long categoryId;

    /**
     * 标签串，规范化成首尾逗号包裹：{@code ",财务,季度,"}。
     * <p>用 TEXT 而非 TEXT[]：项目里没有任何 entity 映射过 PG 数组，首次引入数组映射
     * 配错就是启动期炸；逗号包裹 + {@code LIKE '%,tag,%'} 同样是精确匹配，且无需 GIN。
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String tags;

    @Column(nullable = false)
    private Short pinned;

    /** 1 = ACTIVE，0 = ARCHIVED（业务归档，不是删除）。 */
    @Column(nullable = false)
    private Short status;

    /** 关联的单个档案文件（sys_document.id），可空。 */
    @Column(name = "related_document_id")
    private Long relatedDocumentId;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** 冗余快照：列表页每行不必 join sys_user，且用户改名后历史仍可读。 */
    @Column(name = "owner_name", length = 64)
    private String ownerName;

    @Column(name = "last_editor_id")
    private Long lastEditorId;

    @Column(name = "last_editor_name", length = 64)
    private String lastEditorName;

    /** 乐观锁：每次保存 +1；前端提交时带上读到的值，不匹配即 409。 */
    @Column(nullable = false)
    private Integer version;

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
        if (this.pinned == null) this.pinned = 0;
        if (this.scopeType == null) this.scopeType = SCOPE_PERSONAL;
        if (this.tags == null) this.tags = "";
        if (this.content == null) this.content = "";
        if (this.version == null) this.version = 0;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 是否仍是活跃笔记（未归档）。 */
    public boolean active() {
        return status != null && status == 1;
    }
}