package com.anomalyco.ifinbrain.web.rag.dto;

import com.anomalyco.ifinbrain.web.rag.service.RagQueryService;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** 档案库问答响应。citations 仅包含用户有权看到的资料。 */
@Data
@AllArgsConstructor
public class RagAskResp {

    private String answer;
    private List<RagQueryService.Citation> citations;
    private long elapsedMs;
}
