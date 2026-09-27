package com.anomalyco.ifinbrain.web.design.portal.service;

import com.anomalyco.ifinbrain.web.design.portal.entity.SysPortalBlock;
import com.anomalyco.ifinbrain.web.design.portal.repository.SysPortalBlockRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 内置 6 个内容块 + 2 个 layout 容器幂等 seed。
 * 按 type 唯一性 upsert：已存在则跳过；新增则插入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalBlockSeeder {

    private final SysPortalBlockRepository repo;

    /** 6 + 2 = 8 个内置组件 */
    private static final List<Seed> SEEDS = List.of(
            // -------- stat / 数字卡 --------
            new Seed("stat-card", "Stat Card", "stat", "DataAnalysis", 10,
                    "{\"title\":\"指标\",\"value\":0,\"suffix\":\"\",\"trend\":\"\",\"color\":\"primary\"}",
                    """
                    {"fields":[
                      {"key":"title","label":"标题","type":"string","default":"指标","required":true},
                      {"key":"value","label":"数值","type":"number","default":0,"required":true},
                      {"key":"suffix","label":"后缀","type":"string","default":"","placeholder":"例：% / 元 / 条"},
                      {"key":"trend","label":"趋势","type":"string","default":"","placeholder":"例：+12% / -3"},
                      {"key":"color","label":"颜色","type":"select","default":"primary",
                        "options":[{"label":"主色","value":"primary"},{"label":"成功","value":"success"},
                                   {"label":"警告","value":"warning"},{"label":"危险","value":"danger"},
                                   {"label":"信息","value":"info"}]}
                    ]}"""),

            // -------- chart / 图表卡 --------
            new Seed("chart-card", "Chart Card", "chart", "PieChart", 20,
                    "{\"title\":\"图表\",\"kind\":\"bar\",\"data\":\"[]\",\"xField\":\"\",\"yField\":\"\"}",
                    """
                    {"fields":[
                      {"key":"title","label":"标题","type":"string","default":"图表","required":true},
                      {"key":"kind","label":"图表类型","type":"select","default":"bar",
                        "options":[{"label":"柱状","value":"bar"},{"label":"折线","value":"line"},
                                   {"label":"饼图","value":"pie"}]},
                      {"key":"data","label":"数据（JSON 数组）","type":"textarea","default":"[]",
                        "placeholder":"形如 [{\\"name\\":\\"A\\",\\"value\\":12}]"},
                      {"key":"xField","label":"X 字段","type":"string","default":"name"},
                      {"key":"yField","label":"Y 字段","type":"string","default":"value"}
                    ]}"""),

            // -------- table / 数据表卡（绑定已存 query） --------
            new Seed("data-table", "Data Table", "table", "Grid", 30,
                    "{\"title\":\"表格\",\"queryId\":null,\"pageSize\":20,\"columns\":\"[]\"}",
                    """
                    {"fields":[
                      {"key":"title","label":"标题","type":"string","default":"表格","required":true},
                      {"key":"queryId","label":"查询 ID","type":"number","default":0,
                        "placeholder":"sys_query.id（与数据管理 → 查询库对应）"},
                      {"key":"pageSize","label":"每页行数","type":"number","default":20},
                      {"key":"columns","label":"列配置（JSON）","type":"textarea","default":"[]",
                        "placeholder":"形如 [{\\"key\\":\\"col\\",\\"label\\":\\"列\\",\\"width\\":120}]"}
                    ]}"""),

            // -------- media / 快捷链接 --------
            new Seed("quick-links", "Quick Links", "media", "Link", 40,
                    "{\"title\":\"快捷入口\",\"links\":\"[]\"}",
                    """
                    {"fields":[
                      {"key":"title","label":"标题","type":"string","default":"快捷入口"},
                      {"key":"links","label":"链接列表（JSON）","type":"textarea","default":"[]",
                        "placeholder":"形如 [{\\"label\\":\\"发票列表\\",\\"to\\":\\"/invoice/list\\",\\"icon\\":\\"Tickets\\"}]"}
                    ]}"""),

            // -------- media / 文本块 --------
            new Seed("text-block", "Text Block", "media", "Document", 50,
                    "{\"title\":\"文本块\",\"content\":\"在此输入文本，支持简单段落\",\"variant\":\"plain\"}",
                    """
                    {"fields":[
                      {"key":"title","label":"标题","type":"string","default":"文本块"},
                      {"key":"content","label":"正文","type":"textarea","default":"在此输入文本，支持简单段落"},
                      {"key":"variant","label":"样式","type":"select","default":"plain",
                        "options":[{"label":"普通","value":"plain"},{"label":"提示","value":"callout"},
                                   {"label":"代码","value":"code"}]}
                    ]}"""),

            // -------- media / iframe 嵌入 --------
            new Seed("iframe", "IFrame", "media", "FullScreen", 60,
                    "{\"title\":\"嵌入页\",\"src\":\"\",\"height\":420}",
                    """
                    {"fields":[
                      {"key":"title","label":"标题","type":"string","default":"嵌入页"},
                      {"key":"src","label":"URL","type":"string","default":"","required":true,
                        "placeholder":"https:// 或 /portal/..."},
                      {"key":"height","label":"高度（px）","type":"number","default":420}
                    ]}"""),

            // -------- layout / 行容器（横排） --------
            new Seed("row", "Row", "layout", "Operation", 100,
                    "{\"gap\":16}",
                    """
                    {"fields":[
                      {"key":"gap","label":"列间距（px）","type":"number","default":16}
                    ]}"""),

            // -------- layout / 列容器 --------
            new Seed("col", "Column", "layout", "Grid", 101,
                    "{\"span\":12}",
                    """
                    {"fields":[
                      {"key":"span","label":"占栅格数（1-12）","type":"number","default":12}
                    ]}""")
    );

    @PostConstruct
    @Transactional
    public void seed() {
        int inserted = 0, skipped = 0;
        for (Seed s : SEEDS) {
            if (repo.findByType(s.type).isPresent()) {
                skipped++;
                continue;
            }
            SysPortalBlock b = SysPortalBlock.builder()
                    .type(s.type)
                    .name(s.name)
                    .category(s.category)
                    .icon(s.icon)
                    .sort(s.sort)
                    .enabled((short) 1)
                    .builtIn((short) 1)
                    .defaultConfig(s.defaultConfig)
                    .configSchema(s.configSchema)
                    .build();
            repo.save(b);
            inserted++;
        }
        log.info("[PortalBlockSeeder] inserted={} skipped={}", inserted, skipped);
    }

    private record Seed(String type, String name, String category, String icon, int sort,
                        String defaultConfig, String configSchema) {}
}