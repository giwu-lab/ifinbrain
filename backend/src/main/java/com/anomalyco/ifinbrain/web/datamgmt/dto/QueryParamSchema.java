package com.anomalyco.ifinbrain.web.datamgmt.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 参数 schema 项 — 描述 SQL 中 :param_name 的 UI 输入形态。
 * 存储在 SysQuery.paramsSchemaJson，运行时由前端根据这个数组动态生成表单。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class QueryParamSchema {

    /** 参数名（与 SQL 里的 :name 对应，必填且全 SQL 唯一） */
    private String name;

    /** 显示标签（如 "开始日期"） */
    private String label;

    /** string | number | date | datetime */
    private String type;

    /** 是否必填 */
    private Boolean required;

    /** 默认值（可选） */
    private String defaultValue;

    /** 占位提示（可选） */
    private String placeholder;
}