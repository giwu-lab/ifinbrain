package com.anomalyco.ifinbrain.web.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 档案库问答请求。 */
@Data
public class RagAskReq {

    @NotBlank(message = "问题不能为空")
    @Size(max = 500, message = "问题过长（最多 500 字）")
    private String question;
}
