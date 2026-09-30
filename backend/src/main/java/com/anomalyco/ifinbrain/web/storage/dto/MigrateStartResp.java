package com.anomalyco.ifinbrain.web.storage.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MigrateStartResp {
    private String fromType;
    private String toType;
    private long total;
    private long alreadyDone;       // 跳过幂等已迁
    private long needMigrate;       // 本次实际处理
    private boolean dryRun;
}