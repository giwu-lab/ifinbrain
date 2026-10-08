package com.anomalyco.ifinbrain.web.datamgmt.dto;

import java.util.List;

/**
 * 预检会话的解析结果（第 2 步 → 第 3 步的输入）。
 *
 * @param sessionId    后续 validate / commit 都靠它
 * @param headers      源文件的表头（无表头时是自动生成的「列1…列N」）
 * @param sampleRows   前若干行原始数据，用于映射界面右侧的样例预览
 * @param columns      目标表的列元数据（前端据此渲染可映射列，自增列由 generated 标记置灰）
 */
public record ImportPreviewDto(
        String sessionId,
        String fileName,
        long fileSize,
        String kind,
        List<String> sheetNames,
        String sheetName,
        String encoding,
        List<String> headers,
        List<List<String>> sampleRows,
        int totalRows,
        boolean truncated,
        List<DatasourceSchemaDto.ColumnSchema> columns) {
}