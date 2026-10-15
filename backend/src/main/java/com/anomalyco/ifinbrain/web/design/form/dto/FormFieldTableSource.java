package com.anomalyco.ifinbrain.web.design.form.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 表格（{@link com.anomalyco.ifinbrain.web.design.form.enums.FieldType#TABLE}）的
 * **动态列源**：表格的列由一条查询的结果集列自动匹配出来。
 *
 * <p>和 {@link FormFieldOptionsSource} 是同一套思路的表格版 —— 那边匹配「选项」，
 * 这边匹配「列」：都不内嵌 SQL，只存 queryId，查询改了自动生效。
 *
 * <p><b>必须在本 DTO 上声明</b>：Jackson 默认静默丢弃未知字段，前端多传一个
 * tableSource 而后端不加，保存时配置会被吃掉且接口照样返回 200
 * （同 {@link FormFieldSchema} 里其它新增字段的注释）。
 *
 * <p>与静态 {@code columns} 的关系：**不是覆盖，是选择性同步**。
 * 列的 key 与初始类型以查询结果为准，但用户在属性面板手工调过的东西
 * （列标题、宽度、必填、单元格下拉的选项）在同步时会被保留 ——
 * 规则见前端 {@code utils/form/tableColumns.ts#mergeTableColumns}。
 * 这样「改了查询」不会把设计者半天的手工调整一并清掉。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FormFieldTableSource {

    /** 指向 sys_query.id；不内嵌 SQL，查询改了就自动生效 */
    private Long queryId;

    /** 查询参数值（对应 sys_query.params_schema[].name） */
    private Map<String, String> params;

    /**
     * 动态更新开关。
     *
     * <p>true = 查询列变了自动同步表格列（新增/删除列，保留幸存列的人工调整）；
     * false / null = 不自动同步，只有设计者点「立即同步」时才动一次。
     *
     * <p>默认关是有意的：自动同步会删列，而删列对已经填过数据的单子是有影响的，
     * 不该由系统在后台悄悄做。
     */
    private Boolean autoSync;

    /** 服务端缓存秒数；不填用默认 */
    private Integer cacheSec;
}