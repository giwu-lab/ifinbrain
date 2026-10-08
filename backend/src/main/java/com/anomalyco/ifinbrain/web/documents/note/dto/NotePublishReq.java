package com.anomalyco.ifinbrain.web.documents.note.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 把笔记发布为档案库里的 .md 文件。
 *
 * <p>「发布」是单向的一次性动作，不是双写：笔记仍是唯一真相源，
 * 档案库里那份只是快照式的归档件。需求里「笔记要能变成档案」由它满足。
 */
@Data
public class NotePublishReq {

    /** 目标档案库 id；缺省用笔记自身的 categoryId（LIBRARY scope）。 */
    private Long categoryId;

    /** 档案库内的相对目录（kebab-case 多段用 / 分隔），可空 = 库根。 */
    private String folderPath;
}