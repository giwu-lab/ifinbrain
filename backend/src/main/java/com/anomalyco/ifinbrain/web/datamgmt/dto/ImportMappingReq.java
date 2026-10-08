package com.anomalyco.ifinbrain.web.datamgmt.dto;

import java.util.List;

/**
 * 字段映射 + 导入选项（第 3 步提交）。
 *
 * @param mappings 每个启用项：源表头 → 目标列
 * @param options  空值 / 截断 / 批次等行为开关
 */
public record ImportMappingReq(
        String sessionId,
        List<ImportMapping> mappings,
        ImportOptions options) {

    /**
     * @param sourceHeader 源文件表头名
     * @param targetColumn 目标列名（提交后由 ImportTargetResolver 按元数据校验，不信任前端）
     * @param enabled      false 表示这一列不导入
     */
    public record ImportMapping(String sourceHeader, String targetColumn, Boolean enabled) {}

    /**
     * @param mode          v1 只有 {@code append}；truncate / upsert 属 Phase 2 / 3
     * @param emptyAsNull   空串转 SQL NULL（Excel 里没填 == NULL，默认开）
     * @param truncateString 字符串超长时截断并记 warning；false 则该行判失败
     * @param skipEmptyRow  整行为空则跳过
     * @param batchSize     executeBatch 粒度；由后端夹到 [200, 2000]
     */
    public record ImportOptions(String mode,
                                Boolean emptyAsNull,
                                Boolean truncateString,
                                Boolean skipEmptyRow,
                                Integer batchSize) {}
}