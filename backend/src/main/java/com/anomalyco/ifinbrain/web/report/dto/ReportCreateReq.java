package com.anomalyco.ifinbrain.web.report.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** 新建报表请求。 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReportCreateReq {

    @NotBlank(message = "报表编码不能为空")
    @Size(max = 64, message = "报表编码最长 64")
    private String code;

    @NotBlank(message = "报表名称不能为空")
    @Size(max = 128, message = "报表名称最长 128")
    private String name;

    @Size(max = 512)
    private String description;

    /** 必填：报表引用的 sys_query.id */
    @NotNull(message = "必须绑定一个数据源查询")
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
