package com.anomalyco.ifinbrain.web.datamgmt.common;

import java.util.Locale;
import java.util.Set;

/**
 * information_schema 的 raw type → 业务类型 的推断口径。
 *
 * <p>抽成公共类的原因：{@code DatasourceSchemaService}（浏览表结构）与
 * {@code ImportTargetResolver}（导入时重查元数据做白名单）都要做同一件事。
 * 两处各写一份的代价是：某天给 BOOL_TYPES 补一个类型，浏览界面认得、
 * 导入却不认得，用户会看到「列表里是布尔，导入却报不是布尔」这种灵异现象。
 */
public final class ColumnTypes {

    private ColumnTypes() {}

    private static final Set<String> NUMERIC_TYPES = Set.of(
            "int", "int2", "int4", "int8", "smallint", "integer", "bigint",
            "tinyint", "mediumint",
            "decimal", "numeric", "real", "double", "float",
            "money", "smallmoney");

    private static final Set<String> DATETIME_TYPES = Set.of(
            "date", "time", "datetime", "timestamp", "timestamptz",
            "smalldatetime", "datetime2", "datetimeoffset");

    private static final Set<String> BOOL_TYPES = Set.of("bool", "boolean", "bit");

    /**
     * @return 'string' | 'number' | 'date' | 'datetime' | 'boolean' | 'unknown'
     */
    public static String guess(String dataType) {
        if (dataType == null) return "unknown";
        String t = dataType.toLowerCase(Locale.ROOT);
        // 去掉括号参数（varchar(64) → varchar）
        int paren = t.indexOf('(');
        if (paren > 0) t = t.substring(0, paren).trim();
        if (NUMERIC_TYPES.contains(t)) return "number";
        if (DATETIME_TYPES.contains(t)) return "date".equals(t) ? "date" : "datetime";
        if (BOOL_TYPES.contains(t)) return "boolean";
        return "string";
    }
}