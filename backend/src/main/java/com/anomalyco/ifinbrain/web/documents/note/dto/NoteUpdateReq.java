package com.anomalyco.ifinbrain.web.documents.note.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 更新笔记。
 *
 * <p>{@code version} 必填：这是团队笔记防互相覆盖的唯一一道服务端防线（前端自动保存会带上
 * 打开时读到的值）。不匹配抛 409，前端转冲突态让用户选「看最新 / 覆盖 / 放弃」。
 */
@Data
public class NoteUpdateReq {

    @NotBlank
    @Size(max = 255)
    private String title;

    private String content;

    private String scopeType;

    private Long scopeDeptId;

    private Long categoryId;

    private List<String> tags;

    private Long relatedDocumentId;

    @NotNull
    private Integer version;
}