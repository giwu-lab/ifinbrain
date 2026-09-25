package com.anomalyco.ifinbrain.web.datamgmt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class QueryCreateReq {

    @NotBlank
    private String name;

    private String description;

    @NotNull
    private Long datasourceId;

    @NotBlank
    private String sqlTemplate;

    /** 可空：表示无参数 */
    private List<QueryParamSchema> paramsSchema;

    /** 可空，默认 1（启用） */
    private Short status;
}