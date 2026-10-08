package com.anomalyco.ifinbrain.web.documents.note.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 新建笔记。scopeType 缺省 PERSONAL；TEAM 必带 scopeDeptId，LIBRARY 必带 categoryId。 */
@Data
public class NoteCreateReq {

    @NotBlank
    @Size(max = 255)
    private String title;

    private String content;

    /** PERSONAL / TEAM / LIBRARY / ORG；缺省 PERSONAL */
    private String scopeType;

    /** scopeType=TEAM 时必填 */
    private Long scopeDeptId;

    /** scopeType=LIBRARY 时必填 */
    private Long categoryId;

    private List<String> tags;

    private Long relatedDocumentId;
}