package com.anomalyco.ifinbrain.web.datamgmt.dto;

import com.anomalyco.ifinbrain.web.datamgmt.entity.SysDatasource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DatasourceDto {

    private Long id;
    private String name;
    private String dbType;
    private String host;
    private Integer port;
    private String databaseName;
    private String username;

    /** 永远只回掩码，前端拿不到明文 */
    private String passwordMask;

    /** 'missing' = 未配置 / 'ok' = 已存可解密 / 'failed' = 密钥失效 */
    private String passwordStatus;

    private String remark;
    private Short status;

    /** 最近一次测试结果摘要，3 个字段都给前端做行内展示 */
    private Short lastTestOk;
    private LocalDateTime lastTestAt;
    private String lastTestMsg;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 实体 → DTO；passwordStatus 由 Service 探测后 setPasswordStatus() 单独设置
     */
    public static DatasourceDto fromEntity(SysDatasource e, String passwordStatus, String passwordMask) {
        return DatasourceDto.builder()
                .id(e.getId())
                .name(e.getName())
                .dbType(e.getDbType())
                .host(e.getHost())
                .port(e.getPort())
                .databaseName(e.getDatabaseName())
                .username(e.getUsername())
                .passwordMask(passwordMask)
                .passwordStatus(passwordStatus)
                .remark(e.getRemark())
                .status(e.getStatus())
                .lastTestOk(e.getLastTestOk())
                .lastTestAt(e.getLastTestAt())
                .lastTestMsg(e.getLastTestMsg())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}