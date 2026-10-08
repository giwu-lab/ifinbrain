package com.anomalyco.ifinbrain.web.documents.note.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 笔记响应 DTO。
 *
 * <p>关键取舍：<b>content 只在 detail 返回，list 只给 excerpt</b>。
 * 一页 20 篇 markdown 正文能到几 MB，列表接口带上正文会拖垮翻页。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NoteDto {

    private Long id;
    private String title;

    /** 完整 markdown 正文 —— 仅 detail 接口填充。 */
    private String content;

    /** 正文纯文本摘要（前 200 字）—— list 接口填充，不含 markdown 标记。 */
    private String excerpt;

    /** PERSONAL / TEAM / LIBRARY / ORG */
    private String scopeType;
    private Long scopeDeptId;
    private String scopeDeptName;
    private Long categoryId;
    private String categoryName;

    private List<String> tags;
    private Boolean pinned;
    /** 1=活跃 0=已归档 */
    private Short status;

    private Long relatedDocumentId;
    private String relatedDocumentName;

    private Long ownerId;
    private String ownerName;
    private String lastEditorName;
    private Integer version;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ---- 由 ACL 层填充的「当前用户相对这条笔记」的权限位 ----

    /** 当前用户能否编辑（前端据此决定进编辑态还是只读态）。 */
    private Boolean canEdit;

    /** 当前用户能否改可见性 / 共享名单。 */
    private Boolean canShare;

    /** 是否通过共享名单读到（而非自己的 / scope 命中的）—— 前端可打「共享给我」标签。 */
    private Boolean sharedWithMe;

    /** 当前用户在本篇上的授权等级：OWNER / WRITE / READ / null（靠 scope 读到但未点名）。 */
    private String myGrant;

    /** 当前用户是否就是作者。 */
    private Boolean owner;
}