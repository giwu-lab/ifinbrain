package com.anomalyco.ifinbrain.web.documents.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FolderDto {
    /** 文件夹名（display 用） */
    private String name;
    /** 类目下的完整相对路径（用于 navigate），如 "demo/sub1" */
    private String path;
}
