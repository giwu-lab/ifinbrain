package com.anomalyco.ifinbrain.web.bi.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BI 大屏画布定义（layout_json 的内存形态）。
 *
 * <p>与 {@code frontend/src/api/bi.ts} 的 {@code DashboardLayout} 一一对应，是
 * 设计器 / 播放页 / 后端三方共享的唯一契约。
 *
 * <p><b>为什么用「强类型 + 兜底透传」而不是直接存 JsonNode</b>：强类型让
 * 「字段拼错」在编译期暴露；但 layout 是前端持续演进的设计文档，后端一旦落后一个版本，
 * 严格反序列化会直接炸掉整块画布。所以开放的节点（layout / widget / data 绑定）
 * 都带 {@code extraProps} 透传桶 —— 未知字段原样存下、原样返回，不丢数据。
 */
@Data
@NoArgsConstructor
public class DashboardLayout {

    /** 当前 schema 版本；破坏性变更时 +1 并在前端补 migrate() */
    public static final int VERSION = 1;

    private Integer version;
    private Canvas canvas;
    private Theme theme;
    private List<Widget> widgets;

    /** 未知字段兜底桶（JSON 里出现但本类没声明的 key） */
    private Map<String, Object> extraProps = new LinkedHashMap<>();

    @JsonAnySetter
    public void putExtra(String key, Object value) {
        this.extraProps.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> getExtraProps() {
        return extraProps;
    }

    /** 空画布（新建大屏 / 解析失败时的降级返回） */
    public static DashboardLayout empty() {
        DashboardLayout l = new DashboardLayout();
        l.setVersion(VERSION);
        l.setCanvas(Canvas.defaults());
        l.setTheme(Theme.defaults());
        l.setWidgets(new ArrayList<>());
        return l;
    }

    // ===================== 画布 =====================

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Canvas {
        /** 基准画布宽高（1920x1080），播放页按 scaleMode 缩放到实际屏幕 */
        private Integer width;
        private Integer height;
        private Integer gridCols;
        private Integer gridRows;
        private Integer gap;
        /** fit | fill | stretch */
        private String scaleMode;

        public static Canvas defaults() {
            return new Canvas(1920, 1080, 24, 12, 8, "fit");
        }
    }

    // ===================== 主题 =====================

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Theme {
        /** default | deep-blue | ink | aurora */
        private String preset;
        private Boolean dark;
        /** 强调色，覆盖 preset */
        private String accent;

        public static Theme defaults() {
            return new Theme("default", false, null);
        }
    }

    // ===================== 组件 =====================

    @Data
    @NoArgsConstructor
    public static class Widget {
        /** w_xxxx，设计器生成 */
        private String id;
        /** kpi / bar / line / pie / table / text / image / marquee / divider ... */
        private String type;
        private String name;
        /** 栅格坐标（非像素），换分辨率不塌 */
        private Integer x;
        private Integer y;
        private Integer w;
        private Integer h;
        private Integer z;
        private Boolean locked;
        private Boolean hidden;
        private WidgetStyle style;
        private DataBinding data;
        /** ECharts option 片段（按 type），随 layout 序列化保存 */
        private Map<String, Object> chart;
        /** image / text 等原生组件的额外配置 */
        private Map<String, Object> extra;

        private Map<String, Object> extraProps = new LinkedHashMap<>();

        @JsonAnySetter
        public void putExtra(String key, Object value) {
            this.extraProps.put(key, value);
        }

        @JsonAnyGetter
        public Map<String, Object> getExtraProps() {
            return extraProps;
        }
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class WidgetStyle {
        private String title;
        private Boolean titleVisible;
        /** token 名或 CSS 值 */
        private String background;
        private String borderColor;
        private Integer radius;
        private Integer padding;
        private Boolean transparent;
    }

    // ===================== 数据绑定 =====================

    /**
     * 数据源绑定：强约束指向 {@code sys_query.id}，不内嵌 SQL。
     * 取数统一走 {@code POST /api/bi/data/query}。
     */
    @Data
    @NoArgsConstructor
    public static class DataBinding {
        private Long queryId;
        private Map<String, String> params;
        private Mapping mapping;
        /** none | sum | avg | count | max | min（前端聚合） */
        private String agg;
        private Transform transform;
        /** 服务端缓存 TTL（秒），缺省 30 */
        private Integer cacheSec;

        private Map<String, Object> extraProps = new LinkedHashMap<>();

        @JsonAnySetter
        public void putExtra(String key, Object value) {
            this.extraProps.put(key, value);
        }

        @JsonAnyGetter
        public Map<String, Object> getExtraProps() {
            return extraProps;
        }
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Mapping {
        /** 维度列 key */
        private String x;
        /** 分类列 key */
        private String series;
        /** 指标列 key 列表 */
        private List<String> y;
        /** 饼 / 环图的单值列 */
        private String value;
        /** 标签列 */
        private String name;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Transform {
        private SortBy sortBy;
        private Integer limit;
        /** 饼图等默认取 Top N */
        private Integer topN;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SortBy {
        private String field;
        /** asc | desc */
        private String dir;
    }
}
