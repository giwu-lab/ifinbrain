package com.anomalyco.ifinbrain.web.report.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 报表响应 DTO。 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReportDto {

    private Long id;
    private String code;
    private String name;
    private String description;

    /** 数据源查询 id（SQL 真源在 sys_query） */
    private Long queryId;
    /** 查询名，列表页不用再联查 */
    private String queryName;

    /** 查询头布局：可见字段顺序 / 是否折叠 / 每行字段数 */
    private HeaderLayout header;

    /** 列配置（顺序 = 显示顺序） */
    private List<ColumnCfg> columns;

    /** 查询头默认值 */
    private Map<String, String> defaultParams;

    private Integer pageSize;
    private Integer maxRows;

    /** 0=草稿 / 1=已发布 */
    private Short status;

    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 查询头布局。
     *
     * <p>{@code visible} 是查询 params 的 name 列表（顺序即显示顺序）；
     * 为 null / 空时表示「全部可见，按 query schema 原序」。
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class HeaderLayout {
        /** 可见参数 name 顺序；null = 全显示 */
        private List<String> visible;
        /** 查询头是否折叠（字段多于 4 个时折叠，只留前 4 个 + 展开） */
        private Boolean collapsed;
        /** 每行字段数（栅格），默认 4 */
        private Integer columns;

        public static HeaderLayout defaultLayout() {
            return HeaderLayout.builder().visible(null).collapsed(false).columns(4).build();
        }
    }

    /** 列配置。{@code key} 必须与查询结果列的 key 对上，否则该列按 key 找不到时忽略本配置。 */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ColumnCfg {
        private String key;
        private String label;
        private Integer width;
        /** left | center | right */
        private String align;
        /** 是否冻结在左侧 */
        private Boolean fixed;
        private Boolean visible;
    }
}
