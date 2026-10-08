package com.anomalyco.ifinbrain.web.documents.note.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 共享名单里的一行。 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NoteShareDto {

    private Long userId;
    private String username;
    private String displayName;
    private Long deptId;
    private String deptName;
    /** READ / WRITE */
    private String grantType;
    private LocalDateTime createdAt;
}