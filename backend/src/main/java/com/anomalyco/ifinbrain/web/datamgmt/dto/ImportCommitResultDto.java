package com.anomalyco.ifinbrain.web.datamgmt.dto;

import java.util.List;

/**
 * 导入执行结果。
 *
 * <p>注意语义：{@code errorRows} 非空 <b>不等于</b>失败。v1 是「整批单事务原子提交」，
 * 所以要么全成功（errorRows 为空），要么整体回滚（此时 status=2 且失败明细是「全部行」，
 * 通常来自连接中断这类基础设施故障）。真正的脏数据在预检阶段就已被拦下，
 * 用户看到的是「跳过 N 行」而不是「失败 N 行」。
 *
 * @param status 0=成功 1=部分成功（有行被跳过） 2=失败（已回滚）
 */
public record ImportCommitResultDto(
        Long logId,
        int totalRows,
        int insertedRows,
        int skippedRows,
        int warningCount,
        long durationMs,
        int status,
        String message,
        List<ImportErrorRow> errorRows) {
}