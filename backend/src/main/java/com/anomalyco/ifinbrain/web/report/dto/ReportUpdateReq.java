package com.anomalyco.ifinbrain.web.report.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** 更新报表请求。所有字段可选 —— null = 不改。 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReportUpdateReq {

    @Size(max = 128)
    private String name;

    @Size(max = 512)
    private String description;

    private Long queryId;

    private ReportDto.HeaderLayout header;

    private List<ReportDto.ColumnCfg> columns;

    private Map<String, String> defaultParams;

    @Min(value = 20, message = "每页行数最小 20")
    @Max(value = 2000, message = "每页行数最大 2000")
    private Integer pageSize;

    @Min(value = 0, message = "最大行数不能为负")
    private Integer maxRows;
}
