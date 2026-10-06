package com.anomalyco.ifinbrain.web.bi.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/** 大屏取数入参：queryId 强约束指向 {@code sys_query.id}，不传 SQL。 */
@Data
public class BiDataQueryReq {

    @NotNull
    private Long queryId;

    /** 该查询 params_schema 里的参数值 */
    private Map<String, String> params;

    /** 服务端缓存 TTL（秒）；缺省 30，上限 300 */
    private Integer cacheSec;
}
