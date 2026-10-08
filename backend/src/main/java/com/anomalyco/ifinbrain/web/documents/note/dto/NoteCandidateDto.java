package com.anomalyco.ifinbrain.web.documents.note.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 共享对话框的候选用户。形状对齐 TodoAssigneeOption（只启用的用户，按 username 升序），
 * 额外带 deptId/deptName —— 团队笔记里「把财务部的人挑出来」是最常见的操作。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NoteCandidateDto {

    private Long id;
    private String username;
    private String displayName;
    private Long deptId;
    private String deptName;
}