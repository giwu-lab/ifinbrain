package com.anomalyco.ifinbrain.web.datamgmt.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TestResultDto {

    /** true = 连通 + SELECT 1 通过 */
    private boolean ok;

    /** 服务端版本字符串（MySQL: '8.0.36'，PG: 'PostgreSQL 16.2'，MSSQL: 'Microsoft SQL Server 2019'） */
    private String version;

    /** 整个测试耗时，毫秒 */
    private Long latencyMs;

    /** ok=false 时给出可读错误；ok=true 时给出 DB 标识（如 'mysql://host:3306/db'） */
    private String message;
}