package com.anomalyco.ifinbrain.web.documents.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ArchiveLibraryCreateReq {

    @NotBlank
    @Size(max = 64)
    private String name;

    /** kebab-case：小写字母/数字 + 单连字符，不允许前/后/连续连字符 */
    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
             message = "slug must be kebab-case: lowercase letters/digits separated by single hyphens")
    private String slug;

    /** Element Plus 图标名（PascalCase），可选，默认 Folder */
    @Size(max = 64)
    private String icon;

    /** 同级排序，可选；不传 = max+1 */
    private Integer sort;

    private String remark;

    /** 0 = 禁用，1 = 启用；可选，默认 1 */
    private Short status;
}
