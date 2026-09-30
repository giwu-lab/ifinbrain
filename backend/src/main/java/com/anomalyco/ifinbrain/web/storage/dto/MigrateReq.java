package com.anomalyco.ifinbrain.web.storage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class MigrateReq {
    @NotBlank
    private String fromType;   // "local" | "s3"
    @NotBlank
    private String toType;
    @Positive
    private Integer batchSize = 50;
    /** 不真搬，仅算 SHA + size 用于预览总耗时；默认 false */
    private boolean dryRun = false;
    /** 重跑已 status=ok 的条目仍视为已完成（默认 true）。设 false 会重传（罕见） */
    private boolean idempotent = true;
}