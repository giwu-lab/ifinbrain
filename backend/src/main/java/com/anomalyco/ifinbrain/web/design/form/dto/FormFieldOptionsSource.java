package com.anomalyco.ifinbrain.web.design.form.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 选择类字段的**动态选项源**：下拉/单选/多选的选项由一条查询实时取回。
 *
 * <p><b>必须在本 DTO 上声明</b>：Jackson 默认静默丢弃未知字段，前端多传一个
 * optionsSource 而后端不加，保存时配置会被吃掉且接口照样返回 200。
 *
 * <p>与静态 {@code options} 的关系：两者是「或」而非「叠加」——
 * 配了 optionsSource 就以查询结果为准（见 FormDesignService#validateFields）。
 * 保留静态 options 是为了给「常用项」兜底？不实现叠加，避免两套语义：
 * 有 source 用 source，没 source 用 options。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FormFieldOptionsSource {

    /** 指向 sys_query.id；不内嵌 SQL，查询改了就自动生效 */
    private Long queryId;

    /** 查询参数值（对应 sys_query.params_schema[].name） */
    private Map<String, String> params;

    /** 取哪一列当显示文本（结果集列名） */
    private String labelField;

    /** 取哪一列当值（结果集列名） */
    private String valueField;

    /** 服务端缓存秒数；不填用默认 */
    private Integer cacheSec;
}