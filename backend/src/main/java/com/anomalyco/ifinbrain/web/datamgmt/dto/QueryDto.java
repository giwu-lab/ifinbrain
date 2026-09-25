package com.anomalyco.ifinbrain.web.datamgmt.dto;

import com.anomalyco.ifinbrain.web.datamgmt.entity.SysQuery;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class QueryDto {

    private Long id;
    private String name;
    private String description;
    private Long datasourceId;

    /** 方便前端展示"数据源：财务 ERP" — 直接给名字，前端不用再 join */
    private String datasourceName;
    private String dbType;

    private String sqlTemplate;
    private List<QueryParamSchema> paramsSchema;
    private Short status;

    /** 最近运行摘要 */
    private Short lastRunOk;
    private LocalDateTime lastRunAt;
    private Integer lastRunRowCount;
    private Long lastRunDurationMs;
    private String lastRunMsg;

    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 已发布的查询页面 menu id（null = 未发布）；前端用它直接打开页面 / 反发布 / 删除 */
    private Long publishedMenuId;
    /** 已发布的菜单名 / path（仅展示用） */
    private String publishedMenuName;
    private String publishedMenuPath;
    /** 已发布菜单的 status：0=已取消 1=已发布 */
    private Short publishedMenuStatus;
    /** 已发布菜单的图标 / 排序（编辑模式回填 PublishDialog） */
    private String publishedMenuIcon;
    private Integer publishedMenuSort;

    public static QueryDto from(SysQuery e, String datasourceName, String dbType) {
        return QueryDto.builder()
                .id(e.getId())
                .name(e.getName())
                .description(e.getDescription())
                .datasourceId(e.getDatasourceId())
                .datasourceName(datasourceName)
                .dbType(dbType)
                .sqlTemplate(e.getSqlTemplate())
                .status(e.getStatus())
                .lastRunOk(e.getLastRunOk())
                .lastRunAt(e.getLastRunAt())
                .lastRunRowCount(e.getLastRunRowCount())
                .lastRunDurationMs(e.getLastRunDurationMs())
                .lastRunMsg(e.getLastRunMsg())
                .createdBy(e.getCreatedBy())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}