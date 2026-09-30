package com.anomalyco.ifinbrain.web.storage.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MigrateStatusResp {
    private String fromType;
    private String toType;
    private long total;           // sys_document 中 storage_type=from 的总行数
    private long migrated;        // log status=1
    private long failed;          // log status=2
    private long pending;         // total - migrated - failed
    private String lastError;     // 最新一条失败日志的 error_msg（admin 排查用）
}