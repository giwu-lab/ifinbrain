package com.anomalyco.ifinbrain.web.documents.note.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 列表页 tab 角标。 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NoteStatsDto {

    /** 我写的活跃笔记数 */
    private long mine;
    /** 别人共享给我的活跃笔记数（点名名单命中，排除我自己写的） */
    private long shared;
    /** 我所在部门可见的活跃笔记数（TEAM scope，排除我已计入 mine/shared 的） */
    private long team;
    /** 我已归档的笔记数 */
    private long archived;
}