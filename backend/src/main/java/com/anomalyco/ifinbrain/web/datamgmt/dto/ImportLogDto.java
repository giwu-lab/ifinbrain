package com.anomalyco.ifinbrain.web.datamgmt.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 导入历史行（sys_import_log 的对外视图） */
public record ImportLogDto(
        Long id,
        Long datasourceId,
        String datasourceName,
        String dbType,
        String targetSchema,
        String targetTable,
        String fileName,
        Long fileSize,
        String sheetName,
        String importMode,
        Integer totalRows,
        Integer insertedRows,
        Integer failedRows,
        Integer skippedRows,
        /** 0=成功 1=部分成功 2=失败 */
        Short status,
        String errorMessage,
        /** 失败明细（从 error_detail JSON 还原），可能为空 */
        List<ImportErrorRow> errorRows,
        Long durationMs,
        Long operatorId,
        String operatorName,
        LocalDateTime createdAt) {
}