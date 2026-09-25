package com.anomalyco.ifinbrain.web.datamgmt.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 更新数据源。
 * 注意：password 为 null / "" / "********" 时视为"保持原密码"，不会覆盖密文
 */
@Data
public class DatasourceUpdateReq {

    private String name;

    @Pattern(regexp = "mysql|mssql|postgresql",
             message = "dbType must be one of mysql / mssql / postgresql")
    private String dbType;

    private String host;

    @Min(1)
    private Integer port;

    private String databaseName;

    private String username;

    /** null / "" / "********" → 保留旧值；其他 → 视为新密码覆盖 */
    private String password;

    private String remark;

    private Short status;
}