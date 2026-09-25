package com.anomalyco.ifinbrain.web.datamgmt.dto;

import lombok.Data;

import java.util.Map;

/**
 * 运行查询时携带的入参：key = 参数 name（即 SQL 里的 :name）；value 始终是 string（前端表单原值）。
 * 后端会按 param.type 转成正确类型再 bind。
 */
@Data
public class QueryRunReq {

    /** 可空；为空表示无参数查询 */
    private Map<String, String> paramValues;
}