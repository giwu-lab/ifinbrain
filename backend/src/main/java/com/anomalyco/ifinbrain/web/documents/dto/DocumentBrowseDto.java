package com.anomalyco.ifinbrain.web.documents.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DocumentBrowseDto {
    /** 当前所在路径（类目内的相对路径，空 = 类目根） */
    private String currentPath;
    /** 类目 slug（前端面包屑可用） */
    private String categorySlug;
    /** immediate 子目录（按字母排序） */
    private List<FolderDto> folders;
    /** immediate 文件（按创建时间倒序） */
    private List<DocumentDto> files;
}
