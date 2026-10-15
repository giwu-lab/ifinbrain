# iFinBrain 项目规则（AGENTS.md）

新会话从这里开始。本文件记录项目硬性约束 —— 所有 AI / 自动化工具执行任务前应先扫一遍。

---

## 1. 项目概览

**技术栈**：Spring Boot 4.1.1 + Java 21 · Vue 3 + Vite 6 + Element Plus · Pinia · vue-i18n v11 · PostgreSQL（唯一数据库，dev / prod 同库；SQLite 已弃用）
**包根**：`com.anomalyco.ifinbrain.web`（Service 单类，无 `*Impl.java`）
**前端根**：`frontend/`
**默认凭据**：admin / Admin@123（其他角色由 admin 在「系统设置 → 用户管理」创建）
**开发脚本**：`./scripts/dev.sh restart`（前后端一起）；前端 HMR 自动生效
**目录约定**：
- 后端：`backend/src/main/java/com/anomalyco/ifinbrain/web/{module}/`
- 前端页面：`frontend/src/views/<module>/<view>/Index.vue`

---

## 2. 国际化（i18n）

新增面向用户的页面 / 表单 / 菜单 / 提示文案，**5 处必须同步更新**：

| # | 位置 | 操作 |
|---|---|---|
| 1 | `frontend/src/i18n/locales/zh-CN.ts` | 手工添加中文文案 |
| 2 | `frontend/src/i18n/locales/en-US.ts` | 手工添加英文文案 |

i18n **单一数据源 = TS 文件**：运行时不再从 `sys_i18n_message` 表拉覆盖；新增文案只动这两个文件即可。

**菜单 key 命名**：`<menu>.<path>.<component>`。`BasicLayout.menuName()` 把 component 里的 `/` 替换为 `.`，得到 `menu.data-mgmt.datasource.Index`（保留大小写含 `-`）；而 page 内 i18n 用 camelCase `menu.dataMgmt.datasource.Index`。**两组都给 key 才能覆盖所有路径**。

**相同语义统一文案**：项目内同一概念在多个 i18n 命名空间出现时（如顶级 DIR 在 `menu.X._` 与 `system.permModule.X` 都出现），**以 `menu.X._` 为权威**——所有其他命名空间贴齐，避免「侧边栏写『发票管理』、权限列写『发票』」之类的歧义。改值时一并改两处（含 zh/en 两套）。例外：辅助 permModule（无顶级 DIR 对应的，如 `parser` / `ocr` / `setting` / `file`）保持各自独立短名。

**检查方式**：`grep "t('namespace.x')"` 用到的 key 在两个 TS 文件里 100% 存在；不允许 fallback 到 literal key。

---

## 3. 权限（Permission）

**新功能涉及任何操作必须**：
1. 在 `backend/src/main/resources/db/migration-prod/` 新增 V{n+1}.sql（**只放这一个目录**，见 §4.1）
2. 插入 `sys_permission`（type='API'） + `sys_role_perm`（admin 全拿 / operator / viewer 按业务分配）
3. 插入 `sys_menu`（顶层 DIR 用 `parent_id=0, type='DIR', component=NULL`；子菜单用 `type='MENU', component='<module>/<view>/Index'`）
4. 前端用 `v-if="auth.hasPerm('<code>')"` 守护每个操作按钮
5. 后端 `@Service` 类用 `perm.require('<code>')` 守护每个公开方法

**权限码格式**：`<module>:<resource>:<verb>`，如 `data:datasource:update`。

**角色权限自动 seed**：
- `seedAdminAccess()`：新建/重命名档案库时 admin 自动获得 4 个档案库 perm（JWT 通过 `auth.refreshTokens()` 重发）
- V23 迁移：现存档案库 + 仅 admin seed 档案库级 perm
- V17 绑定：全局 `documents:view/upload/update/delete` 给 admin/operator/viewer

---

## 4. 数据持久化

### 4.1 Flyway 迁移

- 新增表 / 菜单 / 权限必须走 Flyway，不要直接 DDL
- 文件命名 `V{n}__{snake_case}.sql`，n 递增
- **新迁移只写 `db/migration-prod/`**。`FlywayConfig` 把 `db/migration` + `db/migration-prod`
  **两个目录一起加载**（不再按方言分支），Flyway 会合并校验版本号 —— 同一个 V{n} 同时出现在
  两个 location 会直接抛 `Found more than one migration with version {n}`。所以**不能两份都写**。
- `db/migration/` 是 **V2 / V8 / V9 历史冻结目录**（SQLite 时代残留），不再新增任何脚本。
  V10 起（含 V54–V61）全部只在 `db/migration-prod/`。SQLite 已弃用，原 `db/migration-dev/`
  目录已删除（停更于 V44，落后 7 个迁移，且没有 vector 类型跑不了 RAG）。
- 只写 PG 方言，可直接用 `BIGSERIAL` / `JSONB` / `TIMESTAMPTZ` / `vector` / `TEXT[]`，
  不必再为 SQLite 绕路
- 目录名里的 `-prod` 是历史包袱（改名要动 46 个文件的 git 历史）。Flyway checksum 只认版本号
  不认路径，想改成 `db/migration/` 直接 `git mv` 即可，不会有兼容问题
- 软删除**未启用**；删除是真 `DELETE FROM`
- JPA entity 用 `@PrePersist` / `@PreUpdate` 维护 `created_at` / `updated_at`

### 4.2 加密字段

任何"密钥 / 密码 / token"字段必须：
1. 数据库列以 `_enc` 后缀（如 `password_enc`、`api_key_enc`）
2. 用 `CryptoUtil.encrypt(plain)` 落库（`backend/.../security/CryptoUtil.java`）
3. API 返回永远只回掩码 `********` + `passwordStatus: 'missing' | 'ok' | 'failed'`
4. 前端三态密码组件：missing → 必填；ok → "已配置 · 修改"；failed → "密钥失效 · 修改"
5. 更新时 `""` / `null` / `"********"` 视为"保留旧值"，不覆盖密文

master key：`IFINBRAIN_MASTER_KEY` 环境变量（≥32 字节，SHA-256 → AES-256-GCM）。轮换时旧字段无法解密 → `passwordStatus='failed'`，用户必须重填。

---

## 5. 后端结构

- 包根：`com.anomalyco.ifinbrain.web`，**没有** `*Impl.java`
- Lombok：`@Service @RequiredArgsConstructor @Slf4j`
- 写操作 `@Transactional`，读 `@Transactional(readOnly = true)`
- DTO 三件套：`<Resource>Dto`（响应）/ `<Resource>CreateReq` / `<Resource>UpdateReq`（请求，带 `jakarta.validation.constraints.*`）
- Controller `@RestController @RequestMapping("/api/<module>")`，方法返回 `Result.ok(...)` 公共信封
- 新增 Repository：自动扫描 `@EnableJpaRepositories(basePackages = {...})` 在 `iFinBrainApplication.java`

### 5.1 必填字段：前后端双侧校验

用户可见的必填项**两侧都要声明**，只做一侧就会出现「前端一路放行、后端才报错」的割裂。

| 层 | 做法 | 踩过的坑 |
|---|---|---|
| `CreateReq` | id 类字段 `@NotNull` + `@Positive` | 只写 `@NotNull`：传 `0` 会静默通过、再被 service 归一成 NULL，等于绕过必填 |
| `UpdateReq` | **只写 `@Positive`，绝不写 `@NotNull`** | 加 `@NotNull` 会把每一次局部更新打成 400 —— update 是 partial-update 契约，null = 本次不改这个字段 |
| service | 再挡一道不依赖校验器的防线 | 以为 `@Valid` 万无一失：Bean Validation 只覆盖走 `@Valid` 的 controller 入口，service 被别处直接复用时没有这层 |
| 前端表单 | `el-form-item` 必须写 `prop="<field>"`，且 `rules` 里有同名条目 | 只加红星不写 `prop`：el-form 找不到字段，规则静默不生效 |
| 非输入类控件 | `el-select` / `el-tree-select` / `el-switch` 的 rule 要显式写 `trigger: 'change'` | 只写默认 blur：选完之后错误提示不消失，得点别处才消 |

**改动必须两侧联动**：后端一旦用 `@Positive` 关掉「传 0 = 清空」这条语义，前端同名字段的 `?? 0` 兜底要**同步删掉** —— 否则每次编辑都会 400，且报错信息（`deptId: 必须是正数`）完全指不到「你没选部门」这件事上。

**存量不自动回填**：把字段改成必填只约束**新写入**，历史 NULL 行照常存在，所有读取路径（列表 `deptName` 解析、人员选择器的「未分配部门」桶等）必须继续容忍 null。需要清理另跑迁移。

**参考实现**：`UserCreateReq.deptId` / `UserUpdateReq.deptId` / `UserService.requireDeptId` / `views/system/user/Index.vue` 的 `rules.deptId`。

---

## 6. 前端结构

- 路由**完全由 `sys_menu` 驱动**（`frontend/src/router/index.ts`）；新增页面只：
  1. 在 `builtInViews` 加 `'module/view/Index': () => import(...)`
  2. DB 迁移插入 `sys_menu` 行（component 字符串对应上述 key）
- API 文件：`frontend/src/api/<domain>.ts`，导出 `xxxApi = { list, create, update, remove, ... }`
- 页面：`frontend/src/views/<module>/<view>/Index.vue`，`<script setup lang="ts">`
- 权限：`auth.hasPerm('xxx')`，不要自己写 enum
- i18n：`useI18n()` + `t('xxx')`，不要写硬编码中文

### 6.1 顶部多标签页（借鉴 mfish-nocode-view）

每个登录页签到一个 tab；保留蓝紫主色，**不引入** mfish 的橙红 / 黑色 sidebar / 按钮 `→` / pill 状态。

| 概念 | 文件 | 说明 |
|---|---|---|
| Store | `frontend/src/stores/tab.ts` | Pinia store；actions `openTab/closeTab/closeOthers/closeAll/refreshTab/setActive/reset`；sessionStorage key `ifinbrain.tabs.v1` |
| Tab 条 | `frontend/src/components/layout/TabBar.vue` | 滚动 tab + 关闭按钮 + 右键菜单触发器；激活项用 `--color-primary` 蓝紫描边 + 顶部 2px 蓝紫高亮线 |
| 右键菜单 | `frontend/src/components/layout/TabContextMenu.vue` | 刷新当前 / 关闭当前 / 关闭其它 / 关闭所有 |
| 路由联动 | `frontend/src/router/index.ts` `afterEach` | 调 `tabStore.openTab(to)`；白名单内的 path 不入 tab |
| 嵌入位置 | `BasicLayout.vue` `<el-main>` 上方 | `<TabBar>` 仅在 `hasTabs` 时渲染 |
| 缓存 | `BasicLayout.vue` `<router-view>` | `<keep-alive>` 包裹 `<component>`；`:key` 用 `${path}#${refreshCounter}` 保证同 path 不同 query 合并到同一缓存槽，refresh 时强制重建 |
| 持久化 | sessionStorage | 刷新保留；登出 / 跨设备丢失 |

**白名单**（不入 tab）：`/login`、`/403`、`/404`、`/preview/*`、`/preview`

**affix（不可关闭）**：仅 `/dashboard`。`stores/tab.ts` 的 `openTab` 用 `route.path === '/dashboard'` 判定。

**登出清理**：`auth.clear()` 末尾调 `useTabStore().reset()` + `useThemeStore().purge()`，防止上一用户 tab 痕迹 / 主题配色残留到下一个登录的人（见 §7.3.2.1）。

**新增页面无需任何改动**：路由跳转自动登记 tab；只有当该页面需要 `defineOptions({ name: 'Unique' })` 才需补（暂时未启用，因为 `:key` 已保证缓存槽唯一）。

---

## 7. 设计原则

- **避开 SaaS-card 套娃**：主列表区不强制套 `<el-card class="card-table">`，用 plain 边框 div
- **状态在布局中表达**：左侧竖条 / 行内图标 / 等宽 monospace 连接串，不滥用 tag/pill
- **测试反馈就地显示**：行内 icon + 文字摘要；不弹 toast 轰炸
- **数字序 `01/02/03` 仅用于真正的有序步骤**，不当装饰
- **不写** `→` 加在按钮后；**不写** ALL-CAPS eyebrow label；**不写** gradient wash
- 弹窗 `label-width="100px"`（label 长的页面如 parser 用 160px）
- **借鉴边界**：项目借鉴 mfish-nocode-view 的「多标签页 / 顶栏紧凑工具栏 / 沉浸式登录」结构优势；**不引入** 橙红主色、黑色 sidebar、按钮 `→`、ALL-CAPS 副标、SaaS-card 套娃、pill 状态标签

### 7.1 页面骨架（所有管理类页面统一）

**列表页没有页面头部**：标题已由顶部多标签栏 / 面包屑表达，页头重复；操作按钮下沉到表格容器内的第一行 `.table-actions`。

```vue
<template>
  <div class="page-container fade-in-up {domain}-page">
    <div class="table-wrap">
      <div class="table-actions">
        <el-button v-if="auth.hasPerm('xxx:create')" type="primary" :icon="Plus" @click="openCreate">
          {{ t('xxx.create') }}
        </el-button>
      </div>
      <div class="toolbar">...</div>
      <el-table :data="rows" v-loading="loading" border stripe size="small">...</el-table>
      <el-pagination ... />
    </div>
  </div>
</template>
```

| 项 | 标准 |
|---|---|
| 根 class | `page-container fade-in-up {domain}-page`（如 `ds` / `cat` / `user`） |
| 页面头部 | **列表页取消**；操作按钮放容器内 `.table-actions`（主操作靠左，次要靠右，见下） |
| 列表容器 | `.table-wrap`（全局类：1px 边框 + 圆角 + 12px padding + `overflow: visible`），**禁用 `<el-card>` / `.card-table`** |
| 容器内分区 | 查询条件 → 表格 → 分页，三段靠「分割线 + 12px 间隙」分隔（见 §8.5.1） |
| 容器专属布局 | 需要 `flex:1` / `margin-top` 等时叠加自有类：`class="table-wrap docs-table-wrap"`，**只写布局属性**，不重复写 padding / border / background |
| 表格列宽 | 文本列 `min-width` + 数值列 `width`，**至少留一列 flex**，否则表格不撑满（见 §8.1.2） |
| 单元格内容 | 单行 + 省略号，全局生效，页面不用写（见 §8.1.1） |
| 分页 | 放容器内表格下方，标准 layout + page-sizes（见 §8.5） |

**例外**（这些页面保留 `.page-header`，`global.css` 里有全局默认样式）：
表单页（创建/详情）、预览页（`design/*/Preview.vue`）、查询编辑器结果面板（`query/Detail.vue`）、带返回按钮 / 动态名称的页面（`query-page/Index.vue`）、配置页（`system/storage`）、解析设置页（`invoice/parser`）。

**例外**（这些页面的表格**不用** `.table-wrap`，维持各自布局）：
字典管理双栏（`system/dict`，左右分栏）、导入向导三步骤（`import/components/Step*`）、查询结果面板（`query/Detail`）、发票上传表单（`invoice/Upload`）。它们不是标准列表页，硬套容器反而破坏原有布局。

> 新增列表页时，表格的「单行 + 省略号」「容器边框」「容器内分区」「分页间距」都不用写代码 —— 都已在 `global.css` 全局生效（§8.1.1 / §8.5.1）。你只需要关心列宽配的是 `min-width` 还是 `width`。

**`.table-actions` 按钮规范（全局生效，页面只管排布）**：

| 项 | 标准 |
|---|---|
| 主操作 | **最左**，`type="primary"` + `:icon="Plus"`，通常只有「新建」一个 |
| 次要操作 / 搜索框 | **靠右**，用 `<div class="header-actions">` 包裹（`margin-left:auto` 已全局锁死） |
| 新建按钮文案 | 用 `newXxx` 命名（`newUser` / `newRole` / `newMenu` / `newDept` / `newDatasource`）；❌ 禁止复用表格列名 key（如 `colName` = 「名称」，按钮会只显示两个字） |
| 搜索框宽度 | 加 `class="actions-search"`（220px 已锁）；❌ 不写 `style="width:..."` 写死 |
| 间距 | 由 flex `gap` 统一负责，`global.css` 已重置 EP 的 `.el-button + .el-button { margin-left:12px }`（否则叠成 24px） |

```vue
<div class="table-actions">
  <el-button v-if="auth.hasPerm('x:create')" type="primary" :icon="Plus" @click="openCreate">
    {{ t('x.newThing') }}
  </el-button>
  <div class="header-actions">
    <el-input class="actions-search" v-model="keyword" clearable />
    <el-button :icon="Refresh" @click="reload">{{ t('common.refresh') }}</el-button>
  </div>
</div>
```

### 7.2 双层吸顶（`<el-table>` 列表页默认开启）

```css
.{domain}-page .page-header { position: sticky; top: 0; z-index: 30; background: var(--color-bg); }
.{domain}-table-wrap { margin-top: 16px; overflow: visible; }   /* 不要 overflow:hidden */
:deep(.{domain}-table-wrap th.el-table__cell) {
  position: sticky; top: 92px; z-index: 20; background: var(--color-bg) !important;
}
```

`overflow: hidden` 会让 th sticky 只在该容器内有效，**不要加**。

### 7.3 主题系统（界面风格抽屉）

顶栏右侧「调色板」按钮打开右侧抽屉「界面风格」，分两个 tab：
- **主题动画**：4 种布局 / 3 色板 / 6 种动画 / 进度条 / loading
- **界面设置**：亮·暗·跟随系统 / 6 个显示项开关

| 概念 | 文件 | 说明 |
|---|---|---|
| Store | `frontend/src/stores/theme.ts` | Pinia setup-store；schema `ThemeSettings`；localStorage 缓存 + DB `sys_user_preference` 双向同步；500ms 节流 PUT；**smart dark switch** |
| 后端 | `backend/.../preference/{entity,repository,service,controller,dto}` | `GET /api/preference` / `PUT /api/preference` / `DELETE /api/preference` |
| DB | `migration-prod/V39__add_user_preference.sql` | `(user_id PK FK CASCADE, theme_json TEXT, updated_at SMESTAMP)` |
| 抽屉 | `components/layout/StylePanel.vue` | 右侧 `el-drawer` + 两 tab + 重置确认 |
| 4 布局 | `layouts/SidebarLayout.vue` / `TopNavLayout.vue` / `HeaderSidebarLayout.vue` / `FloatingSidebarLayout.vue` | `BasicLayout` 是壳，`<component :is="layoutComp">` 按 store 切换 |
| 3 色板 | `components/layout/StylePanelColorPicker.vue` | 颜色 picker + 透明度滑块 + 12 色预设 |
| 6 动画 | `components/layout/StylePanelAnimation.vue` + `styles/animations.css` | fade/slide/zoom/fade-slide/flip/scale；CSS class 前缀 `sp-{type}` |
| 主题 token | `styles/theme.css` | `:root[data-theme="light"\|"dark"]` 双 token 集（5 层 surface + 半透明白边框） |
| Element Plus 暗色 | `main.ts` 中加载 `element-plus/theme-chalk/dark/css-vars.css` | EP 暗色由 `.dark` 类切换（`AppRoot.vue` 内同步） |

**默认 = 项目现状**：蓝紫主色 `#2563eb` / 白顶栏 / 白侧栏 / 亮色 / fade 动画 / 进度条开 / loading 开。

**借鉴边界**（强制）：抽屉借鉴 mfish 结构，但**不引入**：
- 橙红主色（默认仍是 ifinbrain 蓝紫）
- 黑色 sidebar（默认仍是浅色）
- 按钮 `→` / ALL-CAPS eyebrow / SaaS-card 套娃 / pill 状态

#### 7.3.1 暗色模式 token 设计（深蓝灰 / Linear 风格）

**5 层 surface 递进**（每层 +3–5% 亮度，给层次感）：

| 层 | light | dark | 用途 |
|---|---|---|---|
| `--color-bg` | `#f5f7fb` | `#0b1220` | 页面背景 |
| `--color-bg-elev` | `#ffffff` | `#111827` | 卡片 / 列表 |
| `--color-bg-popover` | `#ffffff` | `#1a2333` | 弹层（popover / dropdown / tooltip） |
| `--color-bg-overlay` | `#ffffff` | `#1f2937` | 抽屉 / 对话框 |

**边框用 rgba**（亮色用实色，暗色用半透明白）：

```css
:root { --color-border: #e5e7eb; --color-border-soft: #f1f3f7; }
:root[data-theme="dark"] {
  --color-border:        rgba(255, 255, 255, 0.10);   /* 主边框 */
  --color-border-soft:   rgba(255, 255, 255, 0.05);   /* 弱化边框 */
  --color-border-strong: rgba(255, 255, 255, 0.16);   /* 强边框（输入框等）*/
}
```

**文字 3 层**：

```css
--color-text:           #f1f5f9;   /* 主文本 */
--color-text-secondary: #cbd5e1;   /* 次要（label / description）*/
--color-text-muted:     #94a3b8;   /* 弱化（placeholder / hint）*/
```

**主色暗色下提亮一档**（避免暗色下 `#2563eb` 太重）：

```css
--color-primary:        #60a5fa;   /* 暗色下 #2563eb → #60a5fa */
--color-primary-hover:  #93c5fd;
--color-primary-soft:   rgba(96, 165, 250, 0.16);
--color-primary-softer: rgba(96, 165, 250, 0.06);
```

**status 暗色用柔和 tinted bgs**：

```css
--color-success:      #34d399;  --color-success-soft:   rgba(52, 211, 153, 0.15);
--color-warning:      #fbbf24;  --color-warning-soft:   rgba(251, 191, 36, 0.16);
--color-danger:       #f87171;  --color-danger-soft:    rgba(248, 113, 113, 0.16);
--color-info:         #818cf8;  --color-info-soft:      rgba(129, 140, 248, 0.16);
```

#### 7.3.2 Smart Dark Switch（用户友好型切换）

**判定基准是「生效模式」是否翻转，不是 `patch.mode` 字面量** —— `light/dark/auto(跟随系统)` 三条路径都要覆盖，判定逻辑收在 `colorsForMode(next, mode)` 一个纯函数里：

```ts
// 生效模式翻转时（_lastEffective !== effectiveMode(merged)），色板跟着翻
commit(nextEff === _lastEffective ? merged : colorsForMode(merged, nextEff));

function colorsForMode(s, mode) {
  if (mode === 'dark') {
    if (looksLightish(s.headerColor)) s.headerColor = DARK_DEFAULTS.headerColor;
    if (looksLightish(s.menuColor))   s.menuColor   = DARK_DEFAULTS.menuColor;
    if (s.primaryColor === LIGHT_DEFAULTS.primaryColor) s.primaryColor = DARK_DEFAULTS.primaryColor;
  } else {
    if (looksDarkish(s.headerColor)) s.headerColor = LIGHT_DEFAULTS.headerColor;
    if (looksDarkish(s.menuColor))   s.menuColor   = LIGHT_DEFAULTS.menuColor;
  }
}
```

**`looksLightish(c)` / `looksDarkish(c)`** 用 RGB 均值判断，避免覆盖用户手动调过的个性化色。

`auto` 模式下系统主题变化（`matchMedia` change）也走 `colorsForMode`，否则会出现「浅色内容 + 深色侧栏」。

#### 7.3.2.1 主题硬约束：模式与色板必须同侧（v1 踩过的坑）

**症状**：demo 登录后侧栏/顶栏是黑的、内容区是白的。

**根因**：`reset()` 一度「mode 归位成 light，但颜色按当前是 dark 取深色默认」——登出时调用它，把
`{mode:'light', headerColor:'#1a1f2e', menuColor:'#0f172a'}` 写进 localStorage；再加上主题缓存是**全局单key**、DB 无记录时直接沿用本地缓存，于是这份坏状态泄漏给了下一个登录的人。

**三条规则**：

| 规则 | 实现 |
|---|---|
| 重置 / 改模式时 mode 与色板必须取**同一侧** | `reset()` 保留当前 mode，再 `colorsForMode(base, effectiveMode(base))` |
| 主题缓存必须**带账号归属** | `ifinbrain.theme.owner` 存用户名；`hydrate(username)` 里 DB 无记录时，只有 owner 匹配才沿用本地缓存，否则回默认 |
| 登出要**整体清掉**主题缓存 | `auth.clear()` 调 `theme.purge()`：删 storage + 落回默认色 + `loaded=false`。**不调后端 DELETE** —— 偏好是上一位用户自己的，下次登录由 hydrate 原样恢复 |

另有 `reconcileStoredColors()` 做**加载自愈**：历史遗留的坏状态（颜色恰好等于 DARK/LIGHT 默认值却和生效模式反着）在 hydrate 时翻回同侧。只认「恰好等于默认值」，自定义色不动。

> 同源问题：`scripts/dark-theme-shoot.mjs` 只改 localStorage 的 `mode`，会绕开色板联动 —— 现在 hydrate 会自愈，暗色截图的侧栏/顶栏也是深色的。

#### 7.3.3 Element Plus 暗色组件全覆盖

`styles/global.css` 末尾 `:root[data-theme="dark"]` 选择器下覆盖 14 个组件：

| 组件 | 覆盖要点 |
|---|---|
| `el-button`（default / text）| bg = elev / border = border-strong / text = --text-secondary |
| `el-tabs` / `el-tabs__item` | active = primary / inactive = text-secondary；active-bar = primary |
| `el-upload-dragger` | **关键**：bg = elev + softer border（修复 upload 页面深色亮块问题）|
| `el-date-editor / el-picker` | 继承 el-input 覆盖；选中态用 primary |
| `el-tree` | hover / is-current 行用 primary-soft |
| `el-dialog` | bg = overlay / header / footer 边框分隔 |
| `el-message-box` | bg = overlay |
| `el-message / el-notification` | 各色 tinted bg + 色文字（success / warning / info / error）|
| `el-alert` | 同上 tinted bg |
| `el-progress` / `el-step` | 文字色 + 进度条底色 |
| `el-skeleton` | shimmer gradient 改为 rgba 255,255,255 半透明 |
| `el-collapse` | header / wrap 用 elev |
| `el-switch / el-slider / el-backtop` | track / button / bg 用 bg-popover |

**加 layout 配套暗色**：`.topnav-header / .hs-header / .floating-root / .tab-bar / .tab-context-menu / .login-page` 都做了 `:root[data-theme="dark"]` 适配，确保 layout 切换时暗色保持一致。

#### 7.3.4 暗色页面硬编码 white 排查

扫描所有 `background: white / #fff` 硬编码，命中处改用 `var(--color-bg-elev)`：

```
views/invoice/Upload.vue          dragger / save-bar
views/invoice/parser/Index.vue    parser-status / reserved-card
views/dashboard/Index.vue         stat-card / shortcut
views/dashboard/components/TodoWidget.vue   widget root / prio-tag
views/system/dict/Index.vue       dict-types / dict-items
views/data-mgmt/query/Index.vue   表格容器
views/todo/task/Index.vue         prio-tag
```

**priority tag 颜色统一改用 status-soft token**（避免暗色下浅黄/浅红背景不协调）：
```css
.prio-high { background: var(--color-danger-soft); color: var(--color-danger); }
.prio-medium { background: var(--color-warning-soft); color: var(--color-warning); }
.prio-low { background: var(--color-primary-soft); color: var(--color-primary); }
```

#### 7.3.5 Playwright 验证

`scripts/dark-theme-shoot.mjs` — 启动 admin 账号 → 11 个页面 × 2 主题 + login，共 **23 张截图**到 `frontend/docs/screenshots/`：

```bash
node scripts/dark-theme-shoot.mjs
# 依赖：playwright 已装在 /opt/node-22/lib/node_modules/playwright
# 依赖：backend（端口 8080）+ vite dev（端口 5173）已在跑
```

**新页面接入**只需检查截图是否出现「亮色块 / 看不清按钮 / 文字对比差」，如有硬编码 white → 改 token，再重新跑脚本验证。

#### 7.3.6 避坑

- ❌ 不要在 view 里写 `background: white / #fff / #f0f4ff` —— 暗色下必炸
- ❌ 不要在暗色视图里用 `linear-gradient(...#fff...)` —— 渐变会让硬编码颜色贯穿
- ❌ 不要在 `[data-theme="dark"]` 下用 `box-shadow: 0 6px 16px rgba(15, 23, 42, ...)` —— shadow 是黑色半透明，暗色页上看不出
- ❌ 不要把 brand 字体放 `text-transform: uppercase` —— AGENTS §7 借鉴边界明确禁止 ALL-CAPS eyebrow
- ✅ 暗色 border 一律用 `rgba(255,255,255,.05/.10/.16)` 三个档位，不要写死具体色
- ✅ 用 `var(--color-bg-elev)` / `var(--color-bg-popover)` 而不是写死 dark 值

### 7.4 弹窗 mask 视觉异常（避坑笔记）

**现象**：`el-dialog` 打开时弹窗本体正常，但弹窗下方有大片浅灰色，看起来弹窗"漂浮在浅背景上"，用户感知为 bug。

**根因**：Element Plus 的 `.el-overlay` 默认用 `var(--el-overlay-color-lighter)` 作为 mask 背景（浅灰半透明黑）；项目 layout-root / body / `.main` 同时用 `var(--color-bg)` 浅灰作背景色。mask 浅灰半透明 + 页面浅灰叠加，结果仍是浅灰 → 弹窗无法在视觉上"压住"页面。

**解决方案**（已全局修复，写在 `frontend/src/styles/global.css`）：
```css
.el-overlay {
  background-color: rgba(15, 23, 42, 0.55) !important;   /* slate-900 55% alpha */
  backdrop-filter: blur(2px);
}
```

**避坑**：下次再看到「弹窗打开时下方有大片灰色」，**不要**去改：
- ❌ el-dialog width / 列宽（无关）
- ❌ .page-container 加 min-height（无关）
- ❌ el-dialog 加 align-center（.el-overlay-dialog 已 flex，无关）
- ❌ el-dialog 加 max-height（无关）
- ❌ 改 dialog width（无关）

那些全是弹窗**本体**或 page 容器的问题，不是 mask 问题。真正的修复点是 `.el-overlay` 的背景。

### 7.5 body 横向滚动条抑制（v1.5.1）

**现象**：预览抽屉打开时，window 底部出现横向滚动条。

**根因**：项目任何页面的某个元素（档案库 el-table 多列总宽 1100px、顶部 tab 溢出、侧栏 fixed 宽度等）都可能把 `body.scrollWidth` 推到 viewport 宽度之外 → body 出横滚条。视觉上"在抽屉底部"，**但其实是 body / window 级别**，不在抽屉内部。

**修复**：在 `styles/global.css` 加 `html, body { overflow-x: hidden; min-width: 0 }`，全局抑制 body 横向滚动条：

- 任何祖先意外 overflow 都会被裁掉，body 不再出现横滚条
- drawer / dialog 是 `position: fixed` 不看 body scrollWidth，不受影响
- el-table 内部 `.el-table__body-wrapper` 仍可独立横向滚动（不动）
- 用户仍可上下滚动 body；只是不能水平滚动 body 整体

**为什么不做更精细的 table 收缩**：项目里 30+ 列表页各有不同 el-table 配置，逐个收缩成本高且易遗漏。`body { overflow-x: hidden }` 一行防御 30 个表。

**与 §7.4 .el-overlay 的协作**：.el-overlay 自己 `width:100vw !important` 不会溢出，但 drawer 内容若超宽会让 .el-overlay 出横滚条（用户实际感受是"抽屉里有横滚条"）。`§10.2` 的 7 层 overflow-x:hidden + `body { overflow-x: hidden }` 双重防御保证——window / overlay / drawer / body 4 个层级都不可能出横滚条。

**v1.5.1 v6 升级**：body 规则从 `overflow-x: hidden` 升级到 `overflow-x: clip !important`（CSS Overflow Level 3）：
- `clip` 比 `hidden` 更彻底：内容被裁剪且**完全不显示 scrollbar**，不创建新的 formatting context
- 兼容 Chrome 90+ / Firefox 81+ / Safari 16+
- 某些浏览器即便 overflow 是 `clip` 也会画默认 scrollbar，所以加 `::-webkit-scrollbar:horizontal { display: none !important }` 兜底
- 配合 §10.2 v6 引入的 JS 探测（dev only），打开 DevTools Console 可看到所有越界元素，根因定位直接

**i18n 命名空间**：`stylePanel.*`（35 keys），2 处同步：zh-CN.ts / en-US.ts（直接改 ts 即可，Vite HMR 立刻生效）。

**新增色/动效**：预设色板在 `StylePanel.vue` 的 `PRIMARY_PRESETS / HEADER_PRESETS / MENU_PRESETS`；新增 transition 模板在 `styles/animations.css` 加 `.sp-{name}-enter/leave-*` 三条规则，然后在 `types.ts` `AnimationType` 联合加成员 + `StylePanelAnimation.vue` 的 `options` 数组加项。

**新增主题存储字段**：在 `ThemeSettings` 加字段 → 默认值放 `DEFAULT_SETTINGS` → 主题/动画生效逻辑在 `applyToRoot()` 里同步 → i18n 同步 2 个 ts 文件即可。

**`@PrePersist/@PreUpdate` 维护 updated_at**：前端 store 在登录后 `theme.hydrate()` 拉 DB 偏好合并；登出 / reset 时调 `DELETE /api/preference`。

### 7.6 登录失败静默（401 的坑）

**现象**：登录页密码填错 / 用户名不存在，点「登录」后**毫无反应** —— 不报错、不跳转、按钮转一下又回来。

**根因**：后端 `BizException(401, ...)` 被 `GlobalExceptionHandler` 映射成 **HTTP 401**（不是 200 + `code:401`），于是走 axios 的 **error 拦截器**；而 `api/http.ts` 里：

```ts
if (status === 401 && !original._retry) {
  const refresh = localStorage.getItem('refreshToken');
  if (!refresh) {
    if (window.location.pathname !== '/login') window.location.href = '/login';
    return Promise.reject(err);   // ← 登录页没有 refreshToken，从这里静默返回，没有 ElMessage
  }
  ...
}
```

登录页天然没有 `refreshToken`，**永远走这条静默分支**。原来 `login/Index.vue` 的 catch 又只有 `console.error`，于是彻底无反馈。

**结论**：登录页的错误提示必须**由页面自己兜**，不能指望全局拦截器。同时**不要透传后端 message**（`"Invalid username or password"` 是英文原文，中文界面上很突兀）。

**已实现**：`login/Index.vue` 用 `loginErrorKey`（存 i18n key 而非文案，切语言时提示跟着变）+ 卡片内 `el-alert type="error"` 就地显示；用户一改输入就清掉，提交前也清一次。i18n：`login.invalidCredentials` / `login.loginFailed`（zh + en 两处）。

> 同理：任何「本来就没有 token」的页面（登录页、刷新页）拿到的 401 都是静默的，别指望 `http.ts` 兜底。

---

## 8. 表格标准

### 8.1 `<el-table>` 样式

所有列表页统一以下属性 + 全局样式（已在 `styles/global.css`），不要 inline 覆盖：

```vue
<el-table :data="rows" v-loading="loading" border stripe size="small">
```

| 元素 | 样式 |
|---|---|
| 表头背景 / 颜色 / 字重 | `var(--color-bg)` / `var(--color-text)` / `600` |
| 表头与单元格字号 | `var(--font-size-sm)` = `13px` |
| 行底边 | `1px solid var(--color-border-soft)` |
| Hover | `var(--color-primary-soft)` |
| **单元格内容** | **单行显示，超出 `...` 截断（全局锁定，见下）** |

**全项目 40 处 `<el-table>` 都必须是 `border stripe size="small`**（2026-10-11 全量核对过）。漏 `size="small"` 的实际差异不是行高 —— `global.css` 的 `.el-table .el-table__cell { padding: 8px 0; line-height: 20px }` 与 EP 的 `.el-table--small .el-table__cell` 同为 `(0,2,0)`，而 global.css 在 EP 之后加载，所以行高两边一致；真正变的是 **`.cell` 的横向内边距**（`small`=8px / 默认=12px）。

**❌ 页面禁止自己覆写行高 / cell 内边距**。踩过的坑：`system/dept` 和 `system/menu` 曾在 scoped 里写

```css
:deep(.dept-row.el-table__row td) { padding: 6px 0 !important; }
:deep(.dept-row.el-table__row .cell) { padding: 0 12px !important; }
```

把行压得比别处**更紧凑**，且 `!important` 盖住全局标准 —— 这两个树表现在只保留 `padding-left` 的层级缩进，行高/cell 全部继承全局。**允许**覆写的只有树形缩进（`tr.el-table__row--level-N > td:first-child { padding-left }`）和表单设计器里的 `TableField.vue`（编辑态刻意紧凑，不属于列表页）。

### 8.1.1 单元格单行 + 省略号（全局强制）

标准来源：`invoice/List.vue`，已提升到 `global.css` 的「标准表格」区块，**全项目 26 处 `<el-table>` 自动生效**，页面无需重复写样式：

```css
.el-table th.el-table__cell > .cell,
.el-table td.el-table__cell > .cell {
  white-space: nowrap; word-break: normal;
  overflow: hidden; text-overflow: ellipsis;
}
```

**为什么必须全局锁**：EP 默认 `.cell { word-break: break-all; white-space: normal }`，长文本会把行撑高、列宽被内容顶开，行高参差。

**两条配套约定**：

- **确需多行的单元格**（长描述 / JSON / 富文本）加 `class="cell-wrap"` 显式覆盖回 `white-space: normal`
- **`el-tag` 单元格**：tag 是 `inline-flex`，父级 ellipsis 裁不到内部文本，`global.css` 已单独给 `.el-tag` + `.el-tag__content` 加了截断规则，页面不用管
- **完整文本查看**：用 `CellText.vue`（点击弹 popover）或 EP 的 `show-overflow-tooltip`

**禁止**：❌ 在 view 里写 `white-space: normal` 覆盖 `.cell`（会破坏行高统一）❌ 靠加宽列宽来「塞下」长文本

### 8.1.2 表格撑满宽度（flex 列模式）

表格右侧留白 = **所有列都绑了 `:width`**。这是 Element Plus 的硬性布局规则，见 `element-plus/es/components/table/src/table-layout.mjs:98-137`：

```js
const flexColumns = flattenColumns.filter((column) => !isNumber(column.width));
if (flexColumns.length > 0 && fit) {
  // 按各列 minWidth 比例瓜分剩余空间 → bodyWidth = max(bodyMinWidth, bodyWidth)
} else {
  bodyWidth = bodyMinWidth;   // ← 全固定宽走这里，右侧留白
}
```

**规则**：

| 列类型 | 写法 | 理由 |
|---|---|---|
| 文本 / 名称 / 描述 | `:min-width="140"` | flex 列，自动吸收剩余空间撑满表格 |
| 数值 / 日期 / 状态 / 操作 | `:width="120"` | 内容定宽，保持列宽整齐 |

- ✅ 一个表格里**至少要有一列 flex 列**（只有 min-width），否则表格不撑满
- ✅ 需要记住用户拖拽后的宽度时，用 `useColumnWidths` 的 `minOf(key, fallback)`（语义=最小宽度）而不是 `widthOf`（语义=固定宽度）；`widthOf` 会把列锁死，重新造成留白
- ❌ 整张表全部 `:width` → 右侧留白（invoice 页踩过这个坑）

### 8.1.3 横向滚动条（底部常驻条带，不遮最后一行）

EP 2.9 的表格横滚条是 `.el-scrollbar` 的绝对定位子元素（`bottom:2px; height:6px`），表格高度自适应时视口高度 == 内容高度，那 8px **直接压在最后一行的下半截上**。已在 `global.css` 全局修掉，页面不用写任何代码：

```css
.el-table--scrollable-x > .el-table__inner-wrapper > .el-table__body-wrapper > .el-scrollbar > .el-scrollbar__wrap {
  box-sizing: border-box;
  border-bottom: 8px solid transparent;
}
```

| 设计点 | 原因 |
|---|---|
| 用 `border-bottom` 而不是 `padding-bottom` | 滚动容器的 border 在 scrollport **之外**、不随内容滚动 → 条带常驻（纵向滚到中间也不跑掉）；`padding-bottom` 在 scrollport **之内**，一滚就消失 |
| 必须显式 `box-sizing: border-box` | EP 给 `max-height` 表格的 wrap 写的是**行内** `max-height`（见 `style-helper.mjs` 的 `scrollbarStyle`），content-box 下边框会加在盒子外侧、被 `overflow:hidden` 裁掉 |
| 只在 `el-table--scrollable-x` 时生效 | EP 按 `layout.scrollX` 打这个类；不横向滚动的表格不会多出一条空白 |
| 选择器写**整条直接子级链** | 将来若单元格内部放了 `el-scrollbar`，后代选择器会误给它也加边框 |

**为什么不做成「滚条真的移到 `.el-table` 之外」**：bar 的祖先 `.el-scrollbar` 与 `.el-table__body-wrapper` 都带 `overflow:hidden`，后者对 `max-height` 表格还要靠它裁出可视高度；要让 bar 溢出必须逐层改 `visible`，会连带破坏 ① 内部纵向滚动表格的 `max-height` 裁剪（表格直接撑高）② `.el-table` 自身的 `border-radius` 裁剪（项目在 `.el-table` 上写的 `overflow: clip`）。当前方案视觉效果一致（滚动条贴着表格底部、位于最后一行下方），但零副作用。

`layout.scrollX` 由列宽与表宽比较得出（`table-layout.mjs:107/122`），与视口**高度**无关，所以这条规则不会引起「加了边框 → 变可滚动 → 去掉边框」的来回抖动。

### 8.2 操作列（高频图标直显 + 删除在 `···` 下拉）

| 操作类型 | 表现 |
|---|---|
| 高频 / 非危险（编辑 / 查看 / 测试 / 授权 ...）| **图标直显** + `el-tooltip` 中文提示 |
| 危险操作（**仅限删除**）| 收纳进 `···` 下拉（`MoreFilled` 触发），下拉里就一项「删除」+ 红色 |

**模板样板**：
```vue
<el-table-column :label="t('common.actions')" :width="WIDTH" align="center" fixed="right">
  <template #default="{ row }">
    <el-tooltip v-if="auth.hasPerm('xxx:verb')" :content="t('xxx.edit')" placement="top">
      <el-button size="small" link :icon="Edit" @click="onCmd('edit', row)" />
    </el-tooltip>
    <el-dropdown v-if="auth.hasPerm('xxx:delete')" trigger="click" @command="(cmd) => onCmd(cmd, row)">
      <el-button :icon="MoreFilled" size="small" link />
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="delete" divided>
            <span class="danger-text">
              <el-icon><Delete /></el-icon>{{ t('common.delete') }}
            </span>
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </template>
</el-table-column>
```

**宽度**：1 图标+下拉 = 96；2 = 120；3 = 160；4+ = 180+（按 32px/图标递增）

**删除确认**（统一 onCmd 入口）：
```ts
async function onCmd(cmd: string, row: T) {
  if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(
        t('xxx.confirmDelete', { name: row.name }),  // 页面特定文案
        t('common.confirmDelete'),                   // 标题统一
        { type: 'warning',
          confirmButtonText: t('common.delete'),
          cancelButtonText: t('common.cancel') }
      );
      await remove(row);
    } catch { /* 用户取消 */ }
  }
}
```

**实现要点**：
- 树节点（el-tree）删除按钮需 `@click.stop` 防冒泡触发节点展开
- 每个文件 script 顶部 `import { Edit, Delete, MoreFilled } from '@element-plus/icons-vue';`
- 风格 CSS：`.danger-text { color: var(--color-danger); }`（global.css 已有）

**已应用页面（10 个）**：

| 页面 | 操作 | 宽度 |
|---|---|---|
| `documents/list/Index.vue` | 预览/下载/重命名/删除 | 160 |
| `documents/archive/Index.vue` | 编辑/授权/删除 | 160 |
| `system/user/Index.vue` | 编辑/删除 | 120 |
| `system/role/Index.vue` | 编辑/删除 | 120 |
| `system/menu/Index.vue` | 新增子/编辑/删除 | 160 |
| `system/dict/Index.vue`（条目表 + 类型树）| 编辑/删除 | 120 |
| `system/dict/I18nPanel.vue` | 编辑/删除 | 120 |
| `invoice/List.vue` | 编辑/删除 | 120 |
| `data-mgmt/datasource/Index.vue` | 测试/编辑/删除 | 160 |
| `data-mgmt/query/Index.vue` | 编辑/复制/删除 | 120 |

**禁止的反模式**：
- ❌ 操作列里直接放文字删除按钮
- ❌ `<el-popconfirm>` 做删除确认
- ❌ 删除按钮和其他危险操作混在图标里

### 8.3 列对齐 / 时间等宽（M4 借鉴）

| 列类型 | `align` | 内部 class |
|---|---|---|
| 数字（金额 / 计数 / ID）| `right` | `tabular` |
| 状态（tag / switch）| `center` | — |
| 操作 | `center` fixed=`right` | — |
| 时间（`yyyy-MM-dd HH:mm:ss`）| 默认（左）| `mono-time` |
| 文本（名称 / 描述 / 文件名）| 默认（左）| — |

**`mono-time` 设计意图**：仅保留 **等宽数字字体 + tabular-nums** 特性（让日期列数字宽度一致对齐，避免「9 比 8 宽」）。字号与表格 cell 默认一致（13px），颜色保留 `--color-text-secondary`（#6b7281 灰），让时间列在表格中视觉上「次要」。

**全局样式**（`styles/global.css` 已锁定）：
```css
.tabular   { font-variant-numeric: tabular-nums; letter-spacing: 0; }
.mono-time { font-family: 'SF Mono', 'JetBrains Mono', Menlo, monospace;
             font-variant-numeric: tabular-nums; font-size: 13px;
             color: var(--color-text-secondary); }
```

**时间显示格式（全项目统一，只能用 `utils/time`）**：

| 语义 | 格式 | 函数 |
|---|---|---|
| **时间戳**（创建/更新/最后运行/截止时间…） | `yyyy-MM-dd HH:mm:ss` | `formatDateTime(s)` |
| **纯日期**（创建日期、账期、归档日…） | `yyyy-MM-dd` | `formatDate(s)` |

```ts
// frontend/src/utils/time.ts —— 两个函数都接受 string / number / Date / null
formatDateTime(row.updatedAt);   // 2026-10-09 14:23:05
formatDate(row.createdDate);     // 2026-10-09
```

**三条硬规则**：

- ❌ **禁止在 view 里手写 `new Date()` + 模板字符串拼时间**。改版前项目里有 8 处这样的实现，格式各不相同（`M/D HH:mm`、`MM-DD HH:mm`、`yyyy-MM-dd HH:mm`、只有 `HH:mm:ss`……），同一个「最后更新」在不同页面长得完全不一样
- ❌ **禁止 `s.replace('T', ' ').slice(0, 19)`** 这种字符串截取。它只对 ISO 字符串有效，遇 `null` 会抛错，且绕过了时区转换
- ✅ 页面里若已有 `formatTime` / `timeText` 之类的本地包装，**函数体只留一行 `return formatDateTime(s)`**，不要在包装里做格式加工 —— 这样全项目的差异只有一处，改一次即可

**时间列的宽度下限**：`yyyy-MM-dd HH:mm:ss` 是 19 个等宽字符，`.mono-time` 13px 下约需 **150px**，加单元格左右 padding 后 **列宽不能低于 170px**，否则会被 §8.1.1 的单行省略号截掉秒数。统一用 `width="175"` 或 `min-width="175"`。

> `formatDateTime` 对非法值返回 `'—'`，无法解析时原样返回入参 —— 脏数据不会让整列表页崩成 `Invalid Date`。

**字体：只用两个 token，不要在 view 里写字体栈**

`styles/global.css` 的 `:root` 里定义了两个，页面一律引用：

| token | 值 | 用途 |
|---|---|---|
| `--font-family` | `'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, …` | 全局无衬线，作用在 `html, body, #app` |
| `--font-mono` | `'SF Mono', 'JetBrains Mono', Menlo, Consolas, ui-monospace, monospace` | 时间列 / 代码块 / 连接串 |

```vue
/* ✅ */  .code { font-family: var(--font-mono); }
/* ❌ */  .code { font-family: ui-monospace, 'JetBrains Mono', monospace; }
```

**为什么必须走 token（改版前的坑）**：

1. **`ui-monospace` 是 CSS 通用族关键字，谁在前谁直接命中**，后面具名字体全部失效。改版前项目里有 9 种排列混用，其中一半以 `ui-monospace` 开头 —— 装了 JetBrains Mono 也永远用不上，同一份代码在不同页面渲染成两种字体。**具名字体必须排在 `ui-monospace` 之前**
2. **`--font-family` 里的 `'Inter'` 只是意图声明**，项目并未加载任何 webfont（无 `@font-face` / `@import` / CDN 引用）。浏览器逐级回退后：Windows 实际命中 **Segoe UI**（中文再字体链接到微软雅黑），macOS 命中苹方，Linux 落到 Roboto / Helvetica Neue / Arial。想真正用上 Inter 必须自行引入 webfont
3. 需要跟随父级无衬线（如富文本区、仪表盘小部件里刻意保持正文字体）时写 `font-family: inherit`，**不要**手抄一遍栈

### 8.4 详情面板 `el-descriptions`（M5 借鉴）

只读详情（vs 编辑表单）使用：

```vue
<el-descriptions :column="2" size="small" label-class-name="extra-info-col">
  <el-descriptions-item label="名称">{{ row.name }}</el-descriptions-item>
  ...
</el-descriptions>
```

`size="small"` + `label-class-name="extra-info-col"` 让 label 加底色（`var(--color-bg)`），值用等宽数字（已在 `global.css` 全局锁定）。

### 8.5 行高 / 分页 / 容器（全局强制）

| 项 | 标准 | 说明 |
|---|---|---|
| **行高** | `padding: 8px 0` + `line-height: 20px` | `global.css` 全局锁死表头与数据行。EP 的 `size="small"` 默认 6px/0，行高随内容浮动；有 tag / chip / 双行文本的表格行高会参差。内容已单行（§8.1.1），固定行高不会截断正常内容 |
| **容器** | `.table-wrap` | 全局类：1px `--color-border-soft` + `--radius-md` + `--color-bg-elev` + `padding: var(--space-6)`（12px）。**内边距挂在容器上**，子元素不再各写各的 padding —— 表格 / 工具栏 / 操作条 / 分页与边框统一留 12px，不会出现「工具栏有间距、表格却贴边」。**`overflow` 必须是 `visible`** —— 用了 `hidden`/`clip` 会创建滚动容器，§7.2 的 `th` sticky 就只在容器内生效，跨页滚动时表头不吸顶。代价是圆角裁不到子元素，由 `.table-wrap > .el-table` 自身圆角补 |
| **分页** | `layout="total,sizes,prev,pager,next,jumper"` + `:page-sizes="[10,20,50,100]"` | **放在 `.table-wrap` 内部**（表格下方），右对齐 + `margin-top: 12px`。全局已锁右对齐与间距 |

**分页的两条易错点**：

- `@size-change` 必须把页码重置回 1，否则切每页条数后停在越界页码上，拿到空列表
- 页面若用 `const size = 20`（常量），绑不了 `v-model:page-size`，要先改成 `ref`

**允许差异的场景**：查询编辑器结果面板（`query/Detail.vue`）、导入向导内嵌小表（`ImportHistory.vue`）可保留精简 layout（无 sizes / jumper）。

### 8.5.1 容器内的分区与间距

容器从上到下是**三段**：查询条件 → 数据表格 → 分页。三段之间靠「**分割线 + 12px 间隙**」双保险分隔，不能只靠一条线（线太弱、留白太紧会糊在一起），也不能只靠留白（没有明确的边界）。

```
┌─ .table-wrap（1px 边框 + 12px padding）─────────┐
│  .toolbar / .table-actions                       │  ← 与容器同底色
│  ─────────── 分割线 ───────────                  │
│         （12px 间隙）                            │
│  <el-table>                                      │
│  ─────────── 分割线 ───────────                  │
│         （12px 间隙）                            │
│  .el-pagination（右对齐）                        │
└──────────────────────────────────────────────────┘
```

`global.css` 已锁死，页面不用写：

```css
.table-wrap { padding: var(--space-6); }           /* = 12px，四周统一 */
.table-wrap > .toolbar,
.table-actions {
  border-bottom: 1px solid var(--color-border-soft);
  margin: -12px -12px 12px;                        /* 负margin让内容铺到容器内边缘 */
  padding: var(--space-6);
}
.table-wrap > .el-pagination {
  border-top: 1px solid var(--color-border-soft);
  padding-top: var(--space-6);
  margin-top: var(--space-6);
}
```

**三个容易做错的点**：

- ❌ **别给 `.toolbar` / `.table-actions` 单独设灰底**（`background: var(--color-bg)`）—— 它们和容器是同一块面，用底色差分层等于凭空多出一层级感，像卡片套卡片（违反 §7.1）。层级靠分割线 + 间距表达
- ❌ **别在页面里重复写 `padding` / `border` / `background`** —— 内边距由容器统一提供；页面专属 wrap 类只写布局属性（`margin-top` / `flex` / `display`）
- ⚠️ **抽屉内可以覆写 `overflow: auto`**（如 `NoteDrawer.vue`）—— 抽屉是独立滚动容器，sticky 表头在容器内生效是预期行为

### 8.5.2 后端真分页（列表页分页栏的硬性要求）

**分页必须是后端真分页**。❌ 绝不用「一次拉全量 + 前端切片」—— 数据会持续增长，今天只有 30 条不代表三个月后还是 30 条，届时全量拉取会同时撑爆后端堆内存和浏览器响应体。

**后端契约**（`common/PageReq.java` + `common/PageResp.java`）：

```java
// Controller
@GetMapping
public Result<PageResp<XxxDto>> list(PageReq req) {
    return Result.ok(xxxService.page(req));
}

// Service：排序必须显式传进去，不能靠 Page 默认主键序兜底
private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("updatedAt"));

@Transactional(readOnly = true)
public PageResp<XxxDto> page(PageReq req) {
    perm.require("xxx:view");
    String kw = req.getKeyword();
    Page<XxxEntity> p = (kw == null || kw.isBlank())
            ? repo.findAllBy(req.toPageable(DEFAULT_SORT))
            : repo.findByNameContainingIgnoreCase(kw, req.toPageable(DEFAULT_SORT));
    return PageResp.of(p.getContent().stream().map(this::toDto).toList(),
            p.getTotalElements(), req.safePage(), req.safeSize());
}
```

| 约定 | 说明 |
|---|---|
| **返回信封** | `common.PageResp<T>` = `{ items, total, page, size }`。不要直接序列化 Spring 的 `Page` —— 它每次迭代可能触发 lazy load，JSON 结构还会随版本漂移 |
| **入参** | `common.PageReq`：通用字段 `page` / `size` / `keyword` / `sortBy` / `sortDir`（⚠️ 类里还带着发票专用的 `seller` / `verificationNo` / 核销日期，那是发票模块的历史包袱，其它模块忽略即可） |
| **size 护栏** | 必须走 `req.toPageable(...)` / `req.safeSize()`：size ≤ 0 会让 `PageRequest.of` 直接抛异常变 500，上限 `MAX_SIZE = 200` |
| **Repository** | 继承的 `findAllBy(Pageable)` + 一个 `findByXxxContainingIgnoreCase(kw, Pageable)` 就够；不要自己拼 `PageRequest` 再 `findAll` |
| **全量入口另开** | 下拉选项类（必须看全量才能渲染）走**独立接口**，别和列表页共用一个 `list()`。范例：`GET /datamgmt/datasources/options`、`GET /bi/dashboard/published`（BI 展示墙轮播用）、`GET /archives`（档案文件页的类目导航 + Pinia store 用） |

**权限是动态拼码时，分页要先把过滤下推到 DB**（档案库 `documents:{slug}:view` 是典型）：

逐行 `perm.has()` 过滤会**先取整表再过滤**，过滤完就无从分页 —— 页大小和 total 都会失真，用户只有 2 条可见数据时第 1 页永远拿不满 size。正确做法是用 `perm.currentPerms()` 取出全量权限码，反推出「有权 slug 集合」，再用 `SlugIn` 交给数据库切片：

```java
// PermissionChecker：新增 currentPerms()，读 AuthPrincipal.getPerms() 全量集合
List<String> slugs = libraryRepo.findEnabledSlugs().stream()      // 只取一列，开销极小
        .filter(s -> myPerms.contains("documents:" + s + ":view"))
        .toList();
if (slugs.isEmpty()) return PageResp.of(List.of(), 0, req.safePage(), req.safeSize());
Page<X> p = repo.findAllByStatusAndSlugInOrderBySortAscIdAsc(ENABLED, slugs, req.toPageable());
```

**不适用分页的例外**（不要硬套）：

- **文件夹浏览器**（`documents/list/Index.vue`）—— 调 `document-browse` 返回当前路径下的子目录 + 文件，是目录树导航不是平铺列表，分页在这里没有语义
- **树形配置页**（`system/{dept,menu,dict}` 用 `el-tree`）、**表单渲染器子表**（`form/fields/TableField.vue`）、**导入向导各步骤**（`import/components/Step*`）、**授权对话框内的角色表**（流程 / 档案库的权限弹窗）
- **详情面板 / 预览页 / 结果面板**（`query/Detail.vue`、`import/components/ImportHistory.vue`）

**前端契约**（`api/*` + `types/index.ts` 的 `PageResp<T>`）：

```ts
list: (params?: { page?: number; size?: number; keyword?: string }) =>
  http.get<unknown, PageResp<XxxItem>>('/xxx', { params }),
```

```vue
<!-- 表格下方，.table-wrap 内（§8.5.1 的第三段） -->
<el-pagination
  v-model:current-page="query.page"
  v-model:page-size="query.size"
  :total="total"
  :page-sizes="[10,20,50,100]"
  layout="total,sizes,prev,pager,next,jumper"
  @current-change="load"
  @size-change="onSizeChange"
/>
```

**分页的四个易错点**：

- `@size-change` 必须重置页码为 1（§8.5）
- `const size = 20` 绑不了 `v-model:page-size`，必须是 `ref`（§8.5）
- **空态判定看 `total` 而不是 `rows.length`** —— 分页后「当前页没数据」≠「库里没数据」。写成 `v-if="rows.length === 0"` 会在第 3 页删空时弹出「还没有数据源，请先创建」的首次使用引导，纯属误导
- **删掉本页最后一条时把页码退一格**，否则停在一个空页上：`if (rows.value.length === 0 && page > 1) page -= 1` 再 reload

### 8.6 角色管理权限列归类（按顶级 DIR）

`views/system/role/Index.vue` 的 `permissionCodes` 列展示按 **sys_menu 顶级 DIR** 归类，不再按 perm code 第一段（`moduleOf`）归类。

- **工具**：`utils/permission.ts: groupByTopMenu(perms, menus)` —— 输入 `sys_menu.flat` 列表，输出按 `sort` 排序的 `TopMenuGroup[]` + orphan bucket（`id=0`）
- **归类算法**：
  1. 抽 `type='DIR' && parent_id=0` 顶级菜单，按 `sort` 升序
  2. 每个 DIR 的 module 前缀集合 = DIR 自带 `permission` 的 `moduleOf` ∪ `TOP_DIR_MODULE_HINTS[t.id]`（兜底：Invoice=invoice / System=system+ocr+setting+file）∪ 子菜单 permission 自动归集
  3. 每个 perm 按 `moduleOf(code)` 命中首个 hints 非空的 DIR
  4. 未命中 → orphan bucket（`id=0`，`moduleKey='_'`）
- **行标题**：复用 `t(\`system.permModule.${group.moduleKey}\`, group.name)`（已收录所有顶级 DIR 的 moduleKey 映射）
- **行内渲染**：
  - 普通 DIR / orphan：前 4 个 verb `<el-tag>` + `+N` 截断标记（沿用 `+N` 圆角 tag 风格）
  - Documents DIR：全局 perm 单独子行 + 档案库按 slug 拆子行（沿用 `archivePermGroups`）
- **orphan 行标题**：`t('system.permOther', '其他权限')` —— 用于发现历史遗留 perm
- **scope**：仅改 `role/Index.vue` 列表列；编辑对话框的 `groupedPermissions` 仍走 `groupByModule`（sectioned checkbox UI 高度自适应）；`PermissionSelect` 组件不动
- **fallback**：`menuApi.flat()` 失败（无 `system:menu:list` perm）→ 静默 `menus.value = []` → `groupByTopMenu` 返回单 orphan bucket，UI 退化为「其他权限」单行展示

---

## 9. 资源文件同步

Spring Boot 运行时从 `backend/target/classes/` 读资源，不读 `src/main/resources/`。新增静态资源后记得跑：

```bash
cd backend && mvn -q process-resources
```

i18n 现已统一收口到前端 TS 文件（zh-CN.ts / en-US.ts），无 `defaults.json` 同步步骤——改文案 = 改 ts → 重启前端 dev server / Vite HMR 即生效。

---

## 10. 特殊坑（dev 模式）

### 10.1 Vite 6 + Worker（Monaco）

Vite 6.4.x 无法解析 `import 'xxx/worker?worker'`（bare-specifier）。`vite-plugin-monaco-editor` 走 CDN 路线，不适合本地部署。

**统一解决**：Monaco 的 5 个 worker（editor / json / css / html / ts）走 **根相对 `/node_modules/...`**，配置集中见 `frontend/src/utils/workers.ts`。

```ts
// workers.ts
const ORIGIN = new URL(import.meta.url).origin;   // http://localhost:5173
return `${ORIGIN}/node_modules/monaco-editor/esm/vs/editor/editor.worker.js`;

self.MonacoEnvironment = {
  getWorker: () => new Worker(ORIGIN + '/node_modules/.../editor.worker.js', { type: 'module' })
};
```

origin 从 `import.meta.url` 取，换机器、换盘符、走 LAN IP 访问都自适应，**不需要任何配置**。单个 worker 仍可用 `VITE_MONACO_*_WORKER_URL` 覆盖（改完要重启 vite）。

**❌ 绝对不要硬编码绝对路径**（踩过的坑）：曾经写死 `/home/coder/workspace/ifinbrain/frontend`，那是某台 Linux 机器的目录。换到 Windows 上该路径不存在 → Vite 走 SPA 兜底返回 `index.html` → worker 请求拿到 `Content-Type: text/html` → 浏览器按 HTML spec 拒绝执行，只报一句看不出真相的 `non-JavaScript MIME type of "text/html"`。实际后果是 Monaco 静默降级到主线程：编辑器还能用，但**没有补全、没有诊断**，很容易被当成「本来就这样」而没人查。

**限制**：当前项目 dev-only（无 prod build 配置）。

### 10.2 档案库预览架构（@file-viewer + el-drawer）

v1.4.x 起档案库预览从「if-else 分发（FilePreviewer / PdfViewer / mammoth / SheetJS / 自研 pdfWorker）」迁移到 **`@file-viewer/preset-office`**，UI 用 `el-drawer 80% rtl`（v1.5.1 把 f2e4e69 那一版的「独立 tab 预览」还原）。

**链路**：

```
documents/list/Index.vue
   └─ 表格行「👁 预览」按钮 → previewVisible=true
       └─ <el-drawer size="80%" direction="rtl" :destroy-on-close="true">
 └─ <ProjectFileViewer :storageKey :filename @download="onPreviewDownload">
 ├─ fetch /api/files/raw?key=...&inline=true (Bearer JWT)
           │       │   → FileController.raw() (per-archive view perm 深度防御)
           │       ├─ ArrayBuffer → viewer.load({ buffer: ab, filename })
           │       └─ @file-viewer/core + renderers:
           │              preset-office  (pdf / word / spreadsheet / presentation / ofd / iwork / wpd / hwp)
           │              renderer-image (jpg / png / gif / svg / tiff / avif / heic / ...)
           │              renderer-text  (md + 48 种 code/text) + capability-text-tools (diff / bundle)
           │              ↑ 异构数组以 `as any` 强转（handler 泛型不一致）
```

**关键设计**：

- **drawer 80% rtl + `:destroy-on-close="true"`**：从 v1.4.0 沿用至今。`destroy-on-close` 是必要的 —— 不开则切换文件 viewer 不会重置，旧 ArrayBuffer 引用无法释放。关闭 drawer → 下次开新文件 viewer 完全重建。
- **per-archive view perm 双重防御**：1) 列表行「预览」按钮 `auth.hasPerm('documents:'+slug+':view')` 守入口；2) `FileController.raw()` 后端二次校验。无需 router guard（drawer 永远在 list 上下文）。
- **preview() 函数极简**：仅设 3 个 ref（`previewKey / previewTitle / previewVisible`），组件树用 `v-if="previewKey"` 保证 viewer 只在打开后挂载，关闭后立即卸载。
- **下载按钮透传**：`ProjectFileViewer` 内 toolbar 的「下载原文件」按钮 emit('download') → 父组件 `onPreviewDownload()` → 复用 `download()` 函数（带 JWT refresh 重试，与表格行下载一致）。
- **PDF 默认配置**：`pdf.initialViewState.scale = 1.5`（不调 fitToWidth，用户可手动点 toolbar）；`theme: 'light'` 强制亮色，不随 OS `prefers-color-scheme` 切换（设计统一性）。
- **6+ 格式渲染**：`@file-viewer/preset-office`（pdf/word/excel/ppt/ofd/iwork/wpd/hwp）+ `@file-viewer/renderer-image`（jpg/png/gif/svg/tiff/avif/heic/...）+ `@file-viewer/renderer-text`（md + 48 种 code/text）+ `@file-viewer/capability-text-tools`（patch diff / git bundle 解析）。
- **对外契约稳定**：`ProjectFileViewer` props (`storageKey` + `filename`) + emit (`download`) 自 v1.4.0 起未变；父组件切换 0 业务代码改动。
- **Worker / WASM 静态路径**：vite plugin 的 `copyAssets:true` 把 PDF.js worker / DOCX worker / XLSX worker / WASM / cmap / fonts 落到 `frontend/public/vendor/`（**gitignored**），不走 `/@fs/` 路由。
- **dompurify 由 file-viewer core 内置**；本仓保留 `dompurify` 仅给设计器 portal 共享使用。
- **`xlsx` 保留**：`data-mgmt/query/Detail.vue:760` SQL 结果导出仍需 `XLSX.writeFile`。
- **退役文件**：`FilePreviewer.vue` / `PdfViewer.vue` / `pdfWorker.ts` / `fileType.ts` / `views/preview/Index.vue` 全部删除；`workers.ts` 不再导出 `PDFJS_WORKER_URL`；`/preview` 路由条目已删除。

**License attribution**：见 `frontend/LICENSE-NOTICE` + `frontend/LICENSE-APACHE-2.0`（Apache-2.0 §4d 要求保留 NOTICE 与 license 文本）。

> ⚠️ **别把这两个文件当成本项目协议**。本项目自身的协议是根目录 `LICENSE`（Apache-2.0 官方原文，202 行 / 11358 字节，SHA256 `CFC7749B…B523D30`）+ 根目录 `NOTICE`（版权 `Copyright 2026 giwu-lab`）。
> `frontend/LICENSE-APACHE-2.0` 是 **file-viewer 第三方组件的裁剪版许可文本**（160 行，末尾多一行 `Copyright 2026 Flyfish Viewer`），
> 比官方原文短，**不能复制到根目录当项目协议**——GitHub 识别不出、条款也缺项。两个文件都不得删除（Apache-2.0 §4d）。

### 10.3 其他

- 时区：JVM 默认 `Asia/Shanghai`（`iFinBrainApplication.main` 强制设置），不依赖宿主机
- vue-i18n v11 用 `.` 作路径分隔符
- 多数据源自建 `DatasourceConnectionService`，**不用** `dynamic-datasource-spring-boot-starter` / `AbstractRoutingDataSource`
- JD 驱动：mysql-connector-j、mssql-jdbc、postgresql（HikariCP 由 starter 间接带入）
- PostgreSQL 是唯一数据库；`org.xerial:sqlite-jdbc` 依赖与 SQLite 迁移目录均已移除。
  外部数据源（数据管理模块）仍可能连 MySQL / MSSQL / SQLite 文件库 —— 那是**用户配的业务库**，
  与本项目主库是两回事，`SqlSafetyChecker` 的 `ATTACH`/`DETACH`/`PRAGMA` 黑名单正是为此保留

---

## 11. 不要做的事

- 不要直接 `git commit` / `git push`（除非用户明确要求）
- 不要写 `*Impl.java` 后缀的 Service
- 不要在迁移里写 SQLite 方言（已弃用）；但**可以**写 PG 专属语法（`JSONB` / `TIMESTAMPTZ` / `vector` / `TEXT[]` / `BIGSERIAL`）
- 不要把硬编码中文 / 英文放进 Vue 模板的 `<template>` 文本节点
- 不要绕过 `PermissionChecker` 直接用 `@PreAuthorize` 字符串权限码
- 不要在 `<el-table>` 操作列里用文字按钮（必须图标 + ···）
- 不要用 `<el-popconfirm>` 做删除确认（统一 `ElMessageBox.confirm`）
- 不要用 axios 接收 blob 响应（已知 bug，会损坏文件）—— 用原生 `fetch().blob()` 或 `arrayBuffer()`
- 不要给所有用户都 broadcast 同一份菜单（每个用户按 perm 过滤；admin 应能看到 Documents DIR 即使没档案库）
- **不要在测试结束后把 `storage.type` 切回 `local` 或用 fake AK/SK 覆盖用户密文**——用户的 S3 配置（内网 MinIO 192.168.3.160:9000 / bucket=ifinbrain）是真实测试环境。验证存储相关修复时：用临时 prefix（如 `bucket=ifinbrain-test`）、隔离 AK/SK、或只测 GET/probe 不写回 sys_setting；**除非用户明确要求重置，否则保留当前 storage 配置不动**。
