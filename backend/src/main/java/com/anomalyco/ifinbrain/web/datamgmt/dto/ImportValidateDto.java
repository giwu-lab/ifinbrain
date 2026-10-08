package com.anomalyco.ifinbrain.web.datamgmt.dto;

import java.util.List;

/**
 * 预检（dry-run）结果 —— 不写库，只回答「如果现在执行会怎样」。
 *
 * @param totalRows     文件里的数据行数
 * @param wouldInsert   通过全部校验、将会被写入的行数
 * @param errorRows     单元格级错误（最多 maxErrorRows 条）
 * @param errorRowCount 出错的<b>行</b>数（errorRows 是单元格粒度，两者不同）
 * @param warningCount  被修过（截断 / 四舍五入）的单元格数
 * @param targetRowCount 目标表当前行数；-1 表示未知（如账号无 SELECT 权限）
 */
public record ImportValidateDto(
        String sessionId,
        int totalRows,
        int wouldInsert,
        int errorRowCount,
        int warningCount,
        List<ImportErrorRow> errorRows,
        long targetRowCount,
        boolean valid) {
}