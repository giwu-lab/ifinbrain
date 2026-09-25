package com.anomalyco.ifinbrain.web.datamgmt.dto;

import lombok.Data;

import java.util.List;

@Data
public class QueryUpdateReq {

    private String name;
    private String description;
    private Long datasourceId;
    private String sqlTemplate;
    private List<QueryParamSchema> paramsSchema;
    private Short status;
}