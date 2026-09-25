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
    }
}