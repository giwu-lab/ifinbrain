package com.anomalyco.ifinbrain.web.documents.note.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 版本快照。
 *
 * <p>列表接口用（不含 content，列表页只显示「第 7 版 · 张三 · 3 分钟前 · 约 1200 字」）；
 * 详情接口带 content，用于查看与回滚。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NoteRevisionDto {

    private Integer revision;
    private String title;
    private String content;
    private String editorName;
    private LocalDateTime createdAt;
}