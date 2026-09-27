package com.anomalyco.ifinbrain.web.documents.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateFolderReq {

    @NotNull
    private Long categoryId;

    /**
     * 档案库下的子路径（kebab-case 单段或多段，用 / 分隔）。
     * 校验在 service 层做（每段匹配 ^[a-z0-9]+(-[a-z0-9]+)*$）。
     */
    @NotBlank
    private String path;
}