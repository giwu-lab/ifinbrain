# BI 大屏设计器 + 大屏展示 — 方案书

> 分支：`BI` · 状态：方案评审中，未开始编码
> 编写时间：2026-10-06

---

## 0. 分支与基线现状（先说结论）

| 项 | 结论 |
|---|---|
| BI 分支 | **已存在**，且与 `main` 同点（`git rev-list --count main..HEAD` = 0），当前 HEAD 已在 BI 上 |
| `dev` 分支 | 落后，只到 `0a400ea chore: initial import`，不是开发主线，**本方案不基于 dev** |
| 工作区 | 有大量未提交改动（dept 模块等），属主线在途工作，BI 开发需注意不要误提交 |
| `migration-dev` | ⚠️ 目录**仍存在于磁盘**（停更于 V44），但 `FlywayConfig` 只加载 `db/migration` + `db/migration-prod`，**不会读它**。原 P-1「补齐 dev 侧 V45–V51」前置任务**作废** |
| 影响 | V52 起迁移只需写一份 PG 方言（落在 `db/migration-prod/`），可直接用 `JSONB` / `TEXT[]` / `BIGSERIAL`；基线干净，无需前置修复 |

---

## 1. 目标与范围

### 1.1 做

1. **大屏定义管理** — 列表 / 新建 / 编辑元信息 / 复制 / 删除 / 发布
2. **大屏设计器** — 拖拽画布、栅格吸附、组件库、图层、属性面板、撤销重做、缩放、JSON 模式、保存、实时数据预览
3. **大屏展示页** — 全屏播放、自动刷新、轮播、多屏展示墙、看板筛选器、无边框模式
4. **图表组件库** — 指标卡 / 柱 / 条 / 折 / 面 / 饼 / 环 / 散点 / 雷达 / 仪表盘 / 漏斗 / 树图 / 词云 / 地图 / 热力 + 明细表 / 文本 / 图片 / 视频 / 滚动列表

### 1.2 明确不做（避免范围蔓延）

| 不做 | 原因 |
|---|---|
| 自己写 SQL 编辑器 / 新建数据集 | 已有 `data-mgmt/query`（SQL 模板 + 参数 schema + `SqlSafetyChecker`）。BI 只**引用** `sys_query.id`，不重复造查询引擎 |
| 数据模型/血缘/多表 join 建模 | 本期靠 `sys_query` 承载，后续再议 |
| 报表 PDF / Excel 排版导出 | 与大屏是两条线 |
| 组件级权限（谁能看哪块） | 本期只做「整屏 view 权限」，组件级留 P2 |
| SSE / WebSocket 实时推送 | 本期用定时轮询，稳定且零基建；P2 再评估 |
| 移动端适配设计器 | 设计器只做桌面，画布固定 1920×1080 基准 |

---

## 2. 总体架构（最大复用点）

**核心思路：BI 不造数据层，完全寄生在已有 `datamgmt` 上。**

```
                        ┌─────────────────────────────────────┐
  sys_query (已有)  ───► │  QueryExecutor (已有)               │
  SQL模板+参数schema       │  SqlSafetyChecker + PreparedStmt  │
                        │  MAX_ROWS=10000 / TIMEOUT=30s       │
                        └──────────────┬──────────────────────┘
                                       │ QueryResultDto {columns,rows,rowCount,truncated}
                                       ▼
                        ┌─────────────────────────────────────┐
                        │  BiDataService（新增，薄层）         │
                        │  · 并发信号量（保护连接池）          │
                        │  · TTL 结果缓存（ConcurrentHashMap） │
                        │  · 按 sys_query.id 复用，不重写 SQL   │
                        └──────────────┬──────────────────────┘
                                       ▼
  ┌────────────────┐          ┌─────────────────┐
  │ BiDashboard     │  layout │ 大屏展示页       │
  │ 设计器 (Vue)    │◄────────►│ /bi/dashboard/…  │
  └────────────────┘  JSON    │ (全屏 canvas)    │
        ▲                        └─────────────────┘
        │ 数据源选择：sys_query 列表
        └────────────────────────────►
```

**为什么这么切**

- `QueryExecutor` 已经把 SELECT-only 校验、`PreparedStatement` 绑定、30s 超时、10000 行截断都做完了 — BI 直接继承这套安全边界，**不新增 SQL 注入面**。
- BI 只是「一个查询结果 → 一张图」的映射层，属于纯前端渲染 + 少量后端编排。

---

## 3. 数据模型

### 3.1 表：`sys_bi_dashboard`（新增 1 张表）

与 `SysFormDesign` 同构（`fields_json` → `layout_json`），保持项目范式一致。

```sql
CREATE TABLE sys_bi_dashboard (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,   -- prod: BIGSERIAL
    code            VARCHAR(64)  NOT NULL UNIQUE,       -- 唯一编码，URL /bi/dashboard/code
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    layout_json     TEXT        NOT NULL,               -- 画布定义（见 §4）
    -- 发布态快照（播放页零解析直读，避免每次 parse 大 JSON）
    theme_json      TEXT,                                -- 主题/配色覆盖
    refresh_interval INTEGER NOT NULL DEFAULT 0,         -- 秒，0=不自动刷新
    status          SMALLINT    NOT NULL DEFAULT 0,      -- 0=草稿 1=已发布
    published_at    TIMESTAMP,
    sort            INTEGER     NOT NULL DEFAULT 0,      -- 轮播排序
    created_by      VARCHAR(64) NOT NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_bi_dash_status ON sys_bi_dashboard (status, sort);
```

**为什么不把组件拆成 `sys_bi_widget` 独立表？**
本期组件与画布强绑定（位置/尺寸/层级都在 layout 里），拆表只会带来 N+1 和一致性成本。等 P2 做「组件模板库 / 跨大屏复用」时再拆。

### 3.2 迁移编号

| 文件 | 目录 | 内容 |
|---|---|---|
| `V52__add_bi_dashboard_table.sql` | `db/migration-prod` | 建表 |
| `V53__seed_bi_dashboard_menu.sql` | `db/migration-prod` | `sys_permission` + `sys_role_perm` + `sys_menu` |

**只写一份** —— SQLite 已弃用、`db/migration-dev/` 已删除，不再有「两份方言」这回事（AGENTS §4.1 已同步更新）。
可直接用 PG 方言：`BIGSERIAL` / `JSONB` / `TIMESTAMPTZ`。

---

## 4. 布局 JSON Schema（核心契约）

这是设计器、播放页、后端三方共享的唯一真相，必须**先冻结 v1**。

```ts
// frontend/src/api/bi.ts（类型定义即文档）
export const LAYOUT_VERSION = 1;

export interface DashboardLayout {
  version: 1;
  canvas: {
    width: 1920; height: 1080;      // 基准画布
    gridCols: 24; gridRows: 12;
    gap: 8;
    scaleMode: 'fit' | 'fill' | 'stretch';   // 播放页缩放策略
  };
  theme: {
    preset: 'default' | 'deep-blue' | 'ink' | 'aurora';  // 4 套大屏主题
    dark: boolean;
    accent?: string;                 // 强调色（覆盖 preset）
  };
  widgets: BiWidget[];
}

export interface BiWidget {
  id: string;              // w_xxxx 客户端生成
  type: WidgetType;
  name: string;
  // 栅格坐标（左上角 + 跨格），不是像素 —— 换分辨率不塌
  x: number; y: number; w: number; h: number;
  z: number;
  locked?: boolean;        // 设计器锁定，防误拖
  hidden?: boolean;

  style: {
    title: string;
    titleVisible: boolean;
    background: string;    // token 或 CSS 值
    borderColor: string;
    radius: number;
    padding: number;
    transparent?: boolean;
  };

  data?: WidgetDataBinding;   // 原生组件可空（image/text/video）
  chart?: Record<string, any>; // echarts option 片段（按 type），序列化保存
}

export interface WidgetDataBinding {
  queryId: number;                       // → sys_query.id（强约束，不内嵌 SQL）
  params?: Record<string, string>;       // 该查询必填参数的固定值/默认值
  mapping: {
    x?: string;         // 维度列 key
    series?: string;    // 分类列 key
    y?: string[];       // 指标列 key 列表
    value?: string;     // 饼/环/词云的单值列
    name?: string;      // 标签列
  };
  agg?: 'none' | 'sum' | 'avg' | 'count' | 'max' | 'min';  // 前端聚合
  transform?: {
    sortBy?: { field: string; dir: 'asc' | 'desc' };
    limit?: number;
    topN?: number;       // 饼图/词云默认取 Top N
  };
  cacheSec?: number;     // 服务端缓存 TTL，默认 30
}
```

### 4.1 `WidgetType` 全集

| 分类 | 类型 | 渲染方式 |
|---|---|---|
| 指标 | `kpi` | 原生 DOM（大数字 + 环比 + 单位），**不走 echarts** |
| 图表 | `bar` `hbar` `stackedBar` `line` `area` `pie` `donut` `scatter` `radar` `gauge` `funnel` `treemap` `wordcloud` `map` `heatmap` | ECharts 按需引入 |
| 原生 | `table`（明细表） `text`（标题/富文本） `image` `video` `marquee`（滚动列表） `divider` | 原生 |

### 4.2 数据流约定

- 后端返回 `QueryResultDto`（`columns[].key/type` + `rows[][]`）
- 前端用 **`echarts.dataset` 组件**接数据（`{source: rows}` + `encode`），而不是手写 `series.data` 映射 —— 这样 `mapping` 只在 dataBinding 里配一次，图表组件零代码差异
- KPI / table / marquee 走手写映射（不用 echarts dataset）

---

## 5. 后端设计

### 5.1 包结构（AGENTS §5：无 `*Impl.java`）

```
backend/src/main/java/com/anomalyco/ifinbrain/web/bi/
├── controller/
│   ├── BiDashboardController.java     /api/bi/dashboard
│   └── BiDataController.java          /api/bi/data
├── dto/
│   ├── BiDashboardDto.java
│   ├── BiDashboardCreateReq.java
│   ├── BiDashboardUpdateReq.java
│   ├── BiDataQueryReq.java            { queryId, params, cacheSec }
│   └── BiDataResp.java                { columns, rows, rowCount, truncated, durationMs, cached, cacheAgeMs }
├── entity/BiDashboardEntity.java      @Table(name="sys_bi_dashboard")
├── repository/BiDashboardRepository.java
└── service/
    ├── BiDashboardService.java         CRUD + publish/unpublish + copy
    └── BiDataService.java              并发限流 + TTL 缓存 + 委托 QueryExecutor
```

### 5.2 API 契约

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| GET | `/api/bi/dashboard` | `bi:dashboard:view` | 列表（含草稿/发布筛选） |
| GET | `/api/bi/dashboard/{id}` | `bi:dashboard:view` | 详情（返回解析后的 `layout` + `theme`） |
| POST | `/api/bi/dashboard` | `bi:dashboard:create` | 新建 |
| PUT | `/api/bi/dashboard/{id}` | `bi:dashboard:update` | 更新（含 layout 全量覆盖） |
| POST | `/api/bi/dashboard/{id}/copy` | `bi:dashboard:create` | 复制（code 加 `_copy`） |
| DELETE | `/api/bi/dashboard/{id}` | `bi:dashboard:delete` | 删除（真 DELETE） |
| POST | `/api/bi/dashboard/{id}/publish` | `bi:dashboard:publish` | 发布 |
| POST | `/api/bi/dashboard/{id}/unpublish` | `bi:dashboard:publish` | 取消发布 |
| **POST** | **`/api/bi/data/query`** | **`bi:dataset:view`** | **大屏取数（核心）** |

`BiDataService.query()` 内部逻辑：

```java
// 1. 缓存命中 → 直接返回（带 cacheAgeMs）
// 2. 信号量.tryAcquire（默认 8 并发，配置项 bi.data.max-concurrent）→ 拿不到直接 429，提示"大屏数据繁忙"
// 3. 复用 QueryService.prepareForRun(queryId) → SysQuery + SysDatasource + plainPassword
// 4. queryExecutor.execute(...)  ← 不重写 SQL、不重写安全校验
// 5. 结果按 queryId+paramsHash 存 ConcurrentHashMap + 写入时间戳，TTL 默认 30s
// 6. finally release
```

**并发限流是必须的**：一个 12 组件的大屏 × 20 个观看者 = 240 次并发查询，不限流会直接打爆 HikariCP 连接池。这是 BI 相对现有 `QueryService` 的**唯一新增风险**，也是要新增 `BiDataService` 这一层的核心理由。

---

## 6. 前端设计

### 6.1 文件清单

```
frontend/src/
├── api/bi.ts                                  # biApi + 全部类型
├── stores/biDesign.ts                         # 设计器 store（照搬 formDesign.ts 范式）
├── views/bi/dashboard/
│   ├── Index.vue                              # 大屏列表（AGENTS §7.1 页面骨架 + §8.2 操作列）
│   ├── Designer.vue                           # 设计器（照搬 design/form/Designer.vue 三栏壳）
│   ├── View.vue                               # 播放/展示页（无布局 chrome）
│   └── Wall.vue                               # 展示墙（多屏轮播，P1）
└── components/bi/
    ├── BiCanvas.vue                           # 栅格画布：拖拽 / 缩放手柄 / 吸附 / 对齐参考线 / 框选
    ├── BiWidgetFrame.vue                      # 组件外壳：标题栏 / 加载 / 错误 / 空态
    ├── BiWidgetRenderer.vue                   # type → 具体组件分发
    ├── BiGridOverlay.vue                      # 栅格底纹 + 吸附对齐参考线（仅设计态渲染）
    ├── widgets/
    │   ├── Wkpi.vue  Wtable.vue  Wtext.vue  Wimage.vue
    │   ├── Wvideo.vue  Wmarquee.vue  Wdivider.vue
    │   └── charts/
    │       ├── ChartBase.vue                  # echarts 实例生命周期（init/setOption/resize/dispose）
    │       ├── ChartBar.vue                   # bar / hbar / stackedBar
    │       ├── ChartLine.vue                  # line / area
    │       ├── ChartPie.vue                   # pie / donut / funnel
    │       ├── ChartScatter.vue
    │       ├── ChartRadar.vue
    │       ├── ChartGauge.vue
    │       ├── ChartTreemap.vue
    │       ├── ChartWordCloud.vue
    │       └── ChartMap.vue                   # map / heatmap
    └── panels/
        ├── BiWidgetPalette.vue                # 左 Tab1：组件库（按分类）
        ├── BiLayerList.vue                    # 左 Tab2：图层（排序 / 显隐 / 锁定 / 删除）
        ├── BiPropsPanel.vue                   # 右：属性（基础 / 样式 / 数据 / 交互 4 段）
        ├── BiDataBindPanel.vue                # 数据源选择 + 字段映射 + 实时预览
        └── BiDashboardMetaPanel.vue           # 大屏级：主题 / 画布 / 刷新间隔 / 轮播排序
```

### 6.2 设计器交互（对齐 `design/form/Designer.vue`）

- **顶栏**：返回 / 标题 / 未保存标记 · 模式切换（`编辑` / `预览` / `JSON`） · 撤销 / 重做 · 缩放（50/75/100/125/150%） · 保存 / 发布
- **左栏**：Tab（组件库 / 图层）
- **中栏**：`BiCanvas`，1920×1080 基准，`transform: scale(zoom)`，画布外灰底
- **右栏**：选中组件时显示属性面板；未选中时显示大屏级属性
- **快捷键**：`Ctrl+S` 保存 · `Ctrl+Z`/`Ctrl+Shift+Z` 撤销重做 · `Delete` 删除 · `Esc` 取消选中 · `Ctrl+D` 复制 · 方向键微调（`Shift+方向键` 加速）· 画布内 `Ctrl+A` 全选
- **撤销栈**：照搬 `formDesign.ts` 的 `history: string[]` + `historyIndex`，每次 commit 存 layout JSON 快照，`cap = 50`

### 6.3 播放页 `View.vue`

- 路由 `/bi/dashboard/view/:id`（静态参数化路由）
- **加入 tab 白名单**（AGENTS §6.1 现有白名单需扩展 `/bi/dashboard/view/*`、`/bi/dashboard/wall`），播放页不占 tab
- 加载后：`POST /api/bi/data/query` 批量取各组件数据 → 渲染
- 自动刷新：`refreshInterval` 秒；失败不打断已有画面，只在组件内显示"数据更新失败 · 上次成功时间"
- 控件（右上角悬浮，`Ctrl` 唤出 / 鼠标靠边唤出）：全屏 / 手动刷新 / 主题切换 / 轮播 / 全屏计数器
- 支持 `?full=1` 隐藏全部控件（用于大屏拼接屏）
- 支持 URL 传参透传到组件查询：`?p_区域=华东` → 所有含 `区域` 参数的组件自动填充

### 6.4 依赖

```jsonc
// package.json 新增
"echarts": "^5.5.1"        // 按需引入，见下
```

**按需引入，控制体积**（`components/bi/charts/echartsEntry.ts`）：

```ts
import * as echarts from 'echarts/core';
import { BarChart, LineChart, PieChart, ScatterChart, RadarChart,
         GaugeChart, FunnelChart, TreemapChart, HeatmapChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent, TitleComponent,
         VisualMapComponent, DatasetComponent, MarkLineComponent } from 'echarts/components';
import { LabelLayout, UniversalTransition } from 'echarts/features';
import { CanvasRenderer } from 'echarts/renderers';
echarts.use([/* ... */]);
```

预计 +280 KB gzip（vs 全量 `echarts` +1MB）。`ChartBase.vue` 统一管 `init` / `setOption` / `ResizeObserver` / `dispose` / `theme`，**杜绝实例泄漏**。

**可选**：`echarts-wordcloud`（+30 KB）—— 词云 P1 再引。

---

## 7. 权限与菜单（AGENTS §3 五步法）

### 7.1 权限码

```
bi:dashboard:view      大屏查看/播放
bi:dashboard:create    新建/复制
bi:dashboard:update    编辑（含 layout 保存）
bi:dashboard:delete    删除
bi:dashboard:publish   发布/取消发布
bi:dataset:view        取数（播放大屏实际需要的后端权限）
```

角色分配：

| 角色 | 权限 |
|---|---|
| admin | 全部 6 个 |
| operator | `view / create / update / publish / dataset:view`（无 delete） |
| viewer | `view / dataset:view` |

### 7.2 菜单结构（V53 迁移插入）

```
DIR  BI  可视化分析
     path=/bi  component=NULL  icon=DataBoard  sort=7
     perm=bi:dashboard:view  type=DIR  status=1

  MENU  大屏管理  /bi/dashboard       component=bi/dashboard/Index    sort=1
  MENU  大屏设计  /bi/dashboard/design/:id  component=bi/dashboard/Designer  sort=2
  MENU  大屏展示  /bi/dashboard/view/:id    component=bi/dashboard/View      sort=3
  MENU  展示墙    /bi/dashboard/wall        component=bi/dashboard/Wall      sort=4
```

`sort` 取 7（现有：Design=3 / Data=4 / Documents=5 / Todo=6）。

### 7.3 路由处理（AGENTS §6：路由由 `sys_menu` 驱动）

在 `router/index.ts` 仿 `data-mgmt/query-page/:menuId(\d+)` 的做法，加**静态参数化路由**接管，`buildRoutesFromMenus` 里 `continue` 跳过精确注册：

```ts
// builtInViews 注册（4 个 key）
'bi/dashboard/Index'    -> views/bi/dashboard/Index.vue
'bi/dashboard/Designer' -> views/bi/dashboard/Designer.vue
'bi/dashboard/View'     -> views/bi/dashboard/View.vue
'bi/dashboard/Wall'     -> views/bi/dashboard/Wall.vue

// 静态路由
{ path: '/bi/dashboard/design/:id(\\d+)', component: biDashboardDesigner }
{ path: '/bi/dashboard/view/:id(\\d+)',   component: biDashboardView }
{ path: '/bi/dashboard/wall',              component: biDashboardWall }
```

`stores/tab.ts` 白名单加：`/bi/dashboard/view/*`、`/bi/dashboard/wall`。

---

## 8. i18n（AGENTS §2）

新增命名空间 `bi.*`，**zh-CN.ts / en-US.ts 两处同步**，零 literal key。

| key | 中文 | 英文 |
|---|---|---|
| `bi.title` | 可视化分析 | Visualization |
| `bi.dash.title` | 大屏管理 | Dashboards |
| `bi.dash.create` | 新建大屏 | New Dashboard |
| `bi.dash.canvasSize` | 画布尺寸 | Canvas Size |
| `bi.dash.refreshInterval` | 刷新间隔 | Refresh Interval |
| `bi.widget.kpi` | 指标卡 | KPI Card |
| `bi.widget.bar` | 柱状图 | Bar Chart |
| `bi.widget.line` | 折线图 | Line Chart |
| `bi.widget.pie` | 饼图 | Pie Chart |
| `bi.widget.table` | 明细表 | Detail Table |
| `bi.props.data` | 数据 | Data |
| `bi.props.style` | 样式 | Style |
| `bi.props.noBinding` | 未绑定数据源 | No data source bound |
| `bi.view.cached` | 数据缓存 {n}s | Cached {n}s |
| `bi.view.staleFailed` | 更新失败 · 上次成功 {t} | Update failed · last {t} |
| `menu.bi._` | 可视化分析 | Visualization |
| `menu.bi.dashboard.Index` | 大屏管理 | Dashboards |
| `menu.bi.dashboard.Designer` | 大屏设计 | Dashboard Designer |
| `menu.bi.dashboard.View` | 大屏展示 | Dashboard View |

**菜单 key 双写规则**（AGENTS §2）：`BasicLayout.menuName()` 把 component 的 `/` 换成 `.` → `menu.bi.dashboard.Index`；page 内用 camelCase → `menu.bi.dashboard.Index`。**本模块恰好一致（无 `-` 的 component 名）**，但仍两端都写。

**同时更新** `system.permModule.bi` = 「可视化分析」，让角色管理权限列（AGENTS §8.5）自动归类。

---

## 9. 视觉规范（强制遵守 AGENTS §7）

| 项 | 要求 |
|---|---|
| 列表页 | `page-container fade-in-up bi-page` + `bi-table-wrap` 边框 div，**禁用 el-card** |
| 操作列 | 高频操作图标直显 + tooltip；**删除收进 `···` 下拉 + 红色**（§8.2） |
| 时间列 | `mono-time` class |
| 数字列 | `align="right"` + `tabular` class |
| 详情面板 | `el-descriptions :column="2" size="small"`（§8.4） |
| 禁止 | `→` 箭头、ALL-CAPS eyebrow、gradient wash、SaaS-card 套娃、pill 状态标签 |
| 弹窗 | `label-width="100px"` |

**大屏主题独立于应用主题**：

- 播放页默认走**大屏专属 preset**（`deep-blue` / `ink` / `aurora`），不受用户 `stores/theme.ts` 影响
- 大屏 preset 定义在大屏自己的 CSS scope 里，用 CSS 变量承接：`--bi-bg` / `--bi-panel` / `--bi-border` / `--bi-accent` / `--bi-text`
- **但禁止在 view 里写死 `#fff`**（AGENTS §7.3.6）：大屏播放页若允许亮色主题，亮色 preset 也必须走 `var(--color-bg-elev)` 而非 `#fff`
- 大屏自带"深色"是刻意的产品能力（不是暗色模式 bug），通过 `layout.theme.preset` 显式控制，与 §7.3 的 `data-theme` 正交

---

## 10. 里程碑

### P-1（前置，0.5 天）：修复迁移基线
补齐 `migration-dev/V45–V51`。**不完成则 dev 环境启动即失败。**

### M1 · 骨架打通（2 天）— P0
- [ ] `npm i echarts`，按需引入封装
- [ ] V52 建表 + 实体/Repo/Service/Controller/DTO
- [ ] V53 菜单权限迁移
- [ ] `api/bi.ts` + 列表页 `Index.vue`
- [ ] 播放页最小版：1 个 KPI + 1 个表格组件，硬编码 layout 验证渲染链路
- ✅ **验收**：列表能建/改/删；播放页能显示真实查询数据；`bi:dashboard:view` 无权限用户看不到菜单且 API 403

### M2 · 设计器内核（4 天）— P0
- [ ] `stores/biDesign.ts`（含 undo/redo/zoom/选中/dirty）
- [ ] `BiCanvas` 拖拽 + 8 向缩放手柄 + 栅格吸附 + 对齐参考线
- [ ] `BiWidgetPalette` / `BiLayerList` / `BiPropsPanel`
- [ ] 保存 / JSON 模式 / 快捷键
- ✅ **验收**：拖 20 个组件、缩放、删除、撤销 10 步不崩；刷新页面布局完整还原；1920×1080 与 2560×1440 下栅格不错位

### M3 · 图表组件全集（3 天）— P1
- [ ] `ChartBase` 生命周期管理（**先做这个**，其余组件依赖它
- [ ] bar/hbar/stackedBar、line/area、pie/donut、scatter、radar、gauge、funnel、treemap、wordcloud、map/heatmap
- [ ] 原生：kpi / table / text / image / video / marquee / divider
- ✅ **验收**：每种类型都能绑定查询出图；组件销毁后 `echarts.getInstanceByDom` 为 null（无泄漏，DevTools 跑一轮）

### M4 · 数据绑定（2 天）— P1
- [ ] `BiDataBindPanel`：选 `sys_query` → 拉 `columns` → 拖拽映射 x/series/y/value
- [ ] 聚合 / 排序 / TopN / 过滤
- [ ] 设计态实时预览（绑定即渲染）
- ✅ **验收**：字段映射有 2 步以上时给出明确的中文提示而不是渲染空白

### M5 · 播放页增强（2 天）— P1
- [ ] 全屏 / 自动刷新 / 手动刷新 / 失败降级（保留上次画面 + 提示）
- [ ] 轮播（多屏 `sort` 排序）+ 展示墙 `Wall.vue`
- [ ] URL 参数透传 / `?full=1` 无边框模式
- [ ] 服务端并发限流 + TTL 缓存（`BiDataService`）
- ✅ **验收**：20 个并发观看者下 12 组件大屏不出 5xx；断网时画面不白屏

### M6 · 打磨（2 天）— P2
- [ ] 4 套大屏主题 preset
- [ ] 大屏导出 PNG（`echarts.getDataURL` 拼接）
- [ ] 列表排序 / 搜索 / 批量发布
- [ ] 暗色模式截图验证（`scripts/dark-theme-shoot.mjs` 纳入 BI 页面）
- [ ] AGENTS.md 补 BI 章节

**合计约 15 人日（M1–M5 P0/P1）+ 2 天 P2**

---

## 11. 风险与应对

| 风险 | 等级 | 应对 |
|---|---|---|
| **并发查询打爆连接池** | 🔴 高 | `BiDataService` 信号量（默认 8）+ TTL 缓存 + 超限返回 429 而非排队饿死；M1 就上，不等 M5 |
| echarts 体积 | 🟡 中 | 按需引入 + `ChartBase` 单一入口；`build` 后用 `vite build --report` 验收 |
| **ECharts 实例泄漏** | 🟡 中 | `ChartBase` 统一 `dispose` + `ResizeObserver.disconnect`；组件 `onBeforeUnmount` 强制调；M3 验收项 |
| 布局 Schema 后期变更 | 🟡 中 | `version` 字段 + `migrate(v)` 升迁函数；每次保存前跑 `validateLayout()` |
| dev/prod 迁移目录脱节 | 🔴 高（已发生） | P-1 补齐；CI 加一条「两目录版本号集合一致」校验 |
| 大屏主题与应用暗色模式打架 | 🟡 中 | preset 独立 CSS 变量，与 `data-theme` 正交；不硬编码 `#fff` |
| 移动端 | 🟢 低 | 本期不做，设计器桌面限定；播放页 `scaleMode='fit'` 兜底 |

---

## 12. 待你确认的决策点

1. **`migration-dev` 补 V45–V51** 是否现在就做？（不做则 BI 无法在 dev 跑通）
2. **大屏是否需要登录态？** 当前设计是走 JWT + `bi:dashboard:view`；若要投到拼接屏长期展示，可能需要「免登录 + 公开大屏」模式（要加公开 token 机制，是独立一块）
3. **数据源口径**：确认复用 `sys_query`（`queryId` 引用），而不是在大屏内内嵌 SQL
4. **主题风格**：4 套 preset（深蓝 / 墨黑 / 极光 / 默认）是否符合预期视觉调性
5. **M3 是否包含地图 / 词云**？（依赖 `geojson` 数据来源和 `echarts-wordcloud`，可拆到 P2）
---

## 13. 实施记录（2026-10-06）

M1 – M5 的功能代码已全部落地并通过编译/类型校验。

### 已交付

| 层 | 内容 |
|---|---|
| 迁移 | `V52__add_bi_dashboard_table.sql`（`sys_bi_dashboard`）、`V53__seed_bi_dashboard_menu.sql`（6 权限码 + 3 角色绑定 + DIR/4 子菜单）。均落在 `db/migration-prod/`，幂等写入，不写死 id |
| 后端 | `web/bi/` 13 个类：`BiDashboardService`（CRUD/复制/发布）、`BiDataService`（TTL 缓存 + `Semaphore.tryAcquire(0)` 并发闸门，超限直接 429 不排队）、`DashboardLayout`（带 `@JsonAnySetter/Getter` 未知字段透传，避免前端加字段被静默丢弃） |
| 前端 | `api/bi.ts`（类型即契约）、`utils/bi/{layout,chartOption}.ts`、`stores/biDesign.ts`、`composables/useBiData.ts`、`components/bi/**`（14 个）、`views/bi/dashboard/{Index,Designer,View,Wall}.vue` |
| 路由 | 静态参数化路由接管 `design/:id`、`view/:id`、`wall`；`buildRoutesFromMenus` 跳过对应 compKey 防双路由冲突 |
| tab | `IGNORED_PREFIXES` 增加 `/bi/dashboard/view` 与 `/bi/dashboard/wall`（播放/展示墙不入 tab） |
| i18n | `bi.*` 全量 + `menu.bi.*` 双写 + `system.permModule.bi`，zh-CN / en-US 同步 |

### 对原方案的关键修正

1. **`QueryService.prepareForRun` 拆分**：原方案说 BI 直接复用它，但它带 `perm.require("data:query:run")`，viewer 角色没有该权限会被误伤。已拆成 `perm.require(...) + loadForRun(id)`，`prepareForRun` 语义不变（export controller 仍走它），BI 走无鉴权版 + 自己入口守 `bi:dataset:view`。
2. **新增 `GET /api/bi/data/queries`**：数据绑定面板需要候选查询列表。权限用 `bi:dataset:view` 而非 `data:query:view`，且**不返回 `sqlTemplate`**（少一个 SQL 泄露面）。
3. **`cqw` 单位不可用**：方案里 KPI 字号用 `clamp(..., 4.2cqw, ...)`，但项目没有 `container-type`，会失效，已改回 px。

### 校验结果

- 后端：`javac` 全量 235 个源文件 **EXIT=0**，BI 模块 29 个 class 全部产出（本机无 Maven，用 `~/.m2` 缓存 jar + argfile 等价编译）
- 前端：`vue-tsc --noEmit` 中 **BI 相关错误 0 个**；剩余 22 个全部位于本轮未触碰的既存文件（`http.ts` / `ProjectFileViewer.vue` / `BasicLayout.vue` / `stores/dict.ts` / `data-mgmt/query/Detail.vue` / `invoice/List.vue` / `system/dict/Index.vue`）
- `vite build` 成功，BI 独立 chunk `735 KB / gzip 246 KB`（echarts 按需引入生效，仅在打开 BI 页时加载）

### 未做 / 待办

- **未执行**：`git commit`、`spring-boot:run`、任何数据库写操作。迁移尚未在真实 PG 上跑过，首次启动才验证
- **地图 / 词云**：按 §12 第 5 点拆到 P2（缺 geojson 数据源 / `echarts-wordcloud`）
- **免登录公开播放**：按 §12 第 2 点本期不做，`BiDashboardEntity` 未留公开 token 字段，后续要加需独立设计
- **AGENTS.md 尚未补 BI 章节**（M6）