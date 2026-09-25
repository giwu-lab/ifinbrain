package com.anomalyco.ifinbrain.web.documents.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新类目。null = 保留原值（PATCH 语义）。
 * 注意：slug 改名会同步修改 sys_menu.path，但不影响 sys_document.category_id 引用。
 */
@Data
public class DocumentCategoryUpdateReq {

    @Size(max = 64)
    private String name;

    @Size(max = 64)
    @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
             message = "slug must be kebab-case: lowercase letters/digits separated by single hyphens")
    private String slug;

    @Size(max = 64)
    private String icon;

    private Integer sort;

    private String remark;

    private Short status;
}
