package com.anomalyco.ifinbrain.web.datamgmt.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class DatasourceCreateReq {

    @NotBlank
    private String name;

    /** 'mysql' | 'mssql' | 'postgresql' —— 前端下拉定值 */
    @NotBlank
    @Pattern(regexp = "mysql|mssql|postgresql",
             message = "dbType must be one of mysql / mssql / postgresql")
    private String dbType;

    @NotBlank
    private String host;

    @NotNull
    @Min(1)
    private Integer port;

    @NotBlank
    private String databaseName;

    @NotBlank
    private String username;

    /** 明文密码，必填（创建时强制要求） */
    @NotBlank
    private String password;

    private String remark;

    private Short status;
}