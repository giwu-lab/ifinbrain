package com.anomalyco.ifinbrain.web.documents.note.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 全量替换共享名单的请求体：{ items: [{ userId, grantType }] }。
 *
 * <p>用「全量替换」而不是增删改三件套：对齐 ArchiveLibraryController.updatePerms 的体例，
 * 前端对话框一次提交整个名单，不需要维护「哪些是新增哪些是删除」的差集状态。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NoteShareUpdateReq {

    private List<Item> items;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Item {
        private Long userId;
        /** READ / WRITE；缺省 READ */
        private String grantType;
    }
}