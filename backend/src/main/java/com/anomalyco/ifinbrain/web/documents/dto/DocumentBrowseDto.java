package com.anomalyco.ifinbrain.web.documents.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DocumentBrowseDto {
    /** 当前所在路径（档案库内的相对路径，空 = 档案库根） */
    private String currentPath;
    /** 档案库 slug（前端面包屑可用） */
    private String categorySlug;
    /** immediate 子目录（按字母排序）—— 不分页：子目录是导航项，数量天然有限，分页会让人找不到目录 */
    private List<FolderDto> folders;
    /** 当前页的 immediate 文件（按创建时间倒序） */
    private List<DocumentDto> files;
    /** 当前层文件总条数，供前端 el-pagination 算总页数（不分 folders） */
    private long total;
    /** 当前页码，从 1 开始 */
    private int page;
    /** 每页条数 */
    private int size;
}
