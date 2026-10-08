package com.anomalyco.ifinbrain.web.datamgmt.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DatasourceSchemaDto {
    private Long datasourceId;
    private String dbType;
    /** 顶层 schema 名（PG:public / MySQL:库名 / MSSQL:dbo） */
    private String defaultSchema;
    private List<TableSchema> tables;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TableSchema {
        /** schema-qualified 名，如 "public.invoice" */
        private String name;
        /** schema 名 */
        private String schema;
        /** 简名（最后一段），如 "发票上" — PG 默认 schema+table */
        private String simpleName;
        private List<ColumnSchema> columns;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ColumnSchema {
        private String name;
        /** raw DB type: varchar / int4 / timestamp 等 */
        private String dataType;
        /** 'string' | 'number' | 'date' | 'datetime' | 'boolean' | 'unknown' */
        private String type;
        private Boolean nullable;

        // ---- 以下三字段为数据导入模块（ImportTypeConverter）新增 ----
        // 没有它们就无法在预检阶段发现「varchar(50) 会被截断」，只能等写库时 DB 才报错。
        // 非字符串列为 null（character_maximum_length 只对 char/varchar 有值）；
        // numeric 列 precision/scale 有值、字符串列为 null。

        /** 字符串最大长度；非字符串列为 null */
        private Integer maxLength;
        /** 数值精度；非数值列为 null */
        private Integer precision;
        /** 数值小数位；非数值列为 null */
        private Integer scale;

        /**
         * 是否为自增 / 生成列（serial、auto_increment、IDENTITY、generated column）。
         * 数据导入页面据此把这类列置灰并从可映射列中排除 ——
         * 往 PG 的 {@code GENERATED ALWAYS AS IDENTITY} 列显式写值会直接报错，
         * 让用户在界面上就选不到它，比在后端报 SQL 错误友好得多。
         */
        private Boolean generated;
    }
}