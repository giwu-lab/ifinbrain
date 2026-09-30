# iFinBrain 项目规则（AGENTS.md）

新会话从这里开始。本文件记录项目硬性约束 —— 所有 AI / 自动化工具执行任务前应先扫一遍。

---

## 1. 项目概览

**技术栈**：Spring Boot 4.1.1 + Java 21 · Vue 3 + Vite 6 + Element Plus · Pinia · vue-i18n v11 · SQLite（开发）/ PostgreSQL（生产）
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
1. 在 `backend/src/main/resources/db/migration-{dev,prod}/` **同步**新增 V{n+1}.sql（两份都要）
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
- 必须同时写两份 `migration-dev/` 和 `migration-prod/`，内容除注释外完全一致
- 列定义 SQLite 兼容：`id INTEGER PRIMARY KEY AUTOINCREMENT`、`VARCHAR(n) NOT NULL`、`SMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP`
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

**登出清理**：`auth.clear()` 末尾调 `useTabStore().reset()`，防止上一用户 tab 痕迹残留。

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

```vue
<template>
  <div class="page-container fade-in-up {domain}-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('xxx.title') }}</h1>
        <p class="page-desc muted">{{ t('xxx.desc') }}</p>
      </div>
      <el-button v-if="auth.hasPerm('xxx:create')" type="primary" :icon="Plus" @click="openCreate">
        {{ t('xxx.create') }}
      </el-button>
    </div>
    <div class="{domain}-table-wrap">
      <el-table :data="rows" v-loading="loading" border stripe size="small">...</el-table>
    </div>
  </div>
</template>
```

| 项 | 标准 |
|---|---|
| 根 class | `page-container fade-in-up {domain}-page`（如 `ds` / `cat` / `user`） |
| 标题字号 / 字重 | `22px` / `700` |
| 按钮对齐 | 标题左 / 按钮右，底部对齐（`align-items: flex-end`） |
| 多按钮 | 包 `<div class="header-actions">` |
| 列表区 | 必须 1px 边框包裹，**禁用 `<el-card>` / `.card-table`** |

**例外**：表单页（创建/详情）按 `el-form` + `el-card`；查询编辑器结果面板（`query/Detail.vue`）保留自有视觉。

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
| Store | `frontend/src/stores/theme.ts` | Pinia setup-store；schema `ThemeSettings`；localStorage 缓存 + DB `sys_user_preference` 双向同步；500ms 节流 PUT |
| 后端 | `backend/.../preference/{entity,repository,service,controller,dto}` | `GET /api/preference` / `PUT /api/preference` / `DELETE /api/preference` |
| DB | `migration-{dev,prod}/V39__add_user_preference.sql` | `(user_id PK FK CASCADE, theme_json TEXT, updated_at SMESTAMP)` |
| 抽屉 | `components/layout/StylePanel.vue` | 右侧 `el-drawer` + 两 tab + 重置确认 |
| 4 布局 | `layouts/SidebarLayout.vue` / `TopNavLayout.vue` / `HeaderSidebarLayout.vue` / `FloatingSidebarLayout.vue` | `BasicLayout` 是壳，`<component :is="layoutComp">` 按 store 切换 |
| 3 色板 | `components/layout/StylePanelColorPicker.vue` | 颜色 picker + 透明度滑块 + 12 色预设 |
| 6 动画 | `components/layout/StylePanelAnimation.vue` + `styles/animations.css` | fade/slide/zoom/fade-slide/flip/scale；CSS class 前缀 `sp-{type}` |
| 主题 token | `styles/theme.css` | `:root[data-theme="light"\|"dark"]` 双 token 集 |
| 元素面板 | `main.ts` 中加载 `element-plus/theme-chalk/dark/css-vars.css` | Element Plus 暗色由 `.dark` 类切换（`AppRoot.vue` 内同步） |

**默认 = 项目现状**：蓝紫主色 `#2563eb` / 白顶栏 / 白侧栏 / 亮色 / fade 动画 / 进度条开 / loading 开。

**借鉴边界**（强制）：抽屉借鉴 mfish 结构，但**不引入**：
- 橙红主色（默认仍是 ifinbrain 蓝紫）
- 黑色 sidebar（默认仍是浅色）
- 按钮 `→` / ALL-CAPS eyebrow / SaaS-card 套娃 / pill 状态

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

### 8.4 详情面板 `el-descriptions`（M5 借鉴）

只读详情（vs 编辑表单）使用：

```vue
<el-descriptions :column="2" size="small" label-class-name="extra-info-col">
  <el-descriptions-item label="名称">{{ row.name }}</el-descriptions-item>
  ...
</el-descriptions>
```

`size="small"` + `label-class-name="extra-info-col"` 让 label 加底色（`var(--color-bg)`），值用等宽数字（已在 `global.css` 全局锁定）。

### 8.5 角色管理权限列归类（按顶级 DIR）

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

**统一解决**：Monaco 的 5 个 worker（editor / json / css / html / ts）经 Vite dev server 的 `/@fs/<绝对路径>` filesystem 路由加载。配置集中见 `frontend/src/utils/workers.ts`，可用 `VITE_PROJECT_ROOT` / `VITE_MONACO_*_WORKER_URL` 环境变量覆盖。

```ts
self.MonacoEnvironment = {
  getWorker: () => new Worker(
    '/@fs/<project>/frontend/node_modules/monaco-editor/esm/vs/editor/editor.worker.js',
    { type: 'module' }
  )
};
```

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

### 10.3 其他

- 时区：JVM 默认 `Asia/Shanghai`（`iFinBrainApplication.main` 强制设置），不依赖宿主机
- vue-i18n v11 用 `.` 作路径分隔符
- 多数据源自建 `DatasourceConnectionService`，**不用** `dynamic-datasource-spring-boot-starter` / `AbstractRoutingDataSource`
- JD 驱动：mysql-connector-j、mssql-jdbc、postgresql（HikariCP 由 starter 间接带入）
- SQLite 默认开发库；生产支持 PG / H2；FK CASCADE 需 `PRAGMA foreign_keys = ON`

---

## 11. 不要做的事

- 不要直接 `git commit` / `git push`（除非用户明确要求）
- 不要写 `*Impl.java` 后缀的 Service
- 不要在迁移里写方言特定语法（保持 SQLite/PG 兼容）
- 不要把硬编码中文 / 英文放进 Vue 模板的 `<template>` 文本节点
- 不要绕过 `PermissionChecker` 直接用 `@PreAuthorize` 字符串权限码
- 不要在 `<el-table>` 操作列里用文字按钮（必须图标 + ···）
- 不要用 `<el-popconfirm>` 做删除确认（统一 `ElMessageBox.confirm`）
- 不要用 axios 接收 blob 响应（已知 bug，会损坏文件）—— 用原生 `fetch().blob()` 或 `arrayBuffer()`
- 不要给所有用户都 broadcast 同一份菜单（每个用户按 perm 过滤；admin 应能看到 Documents DIR 即使没档案库）
- **不要在测试结束后把 `storage.type` 切回 `local` 或用 fake AK/SK 覆盖用户密文**——用户的 S3 配置（内网 MinIO 192.168.3.160:9000 / bucket=ifinbrain）是真实测试环境。验证存储相关修复时：用临时 prefix（如 `bucket=ifinbrain-test`）、隔离 AK/SK、或只测 GET/probe 不写回 sys_setting；**除非用户明确要求重置，否则保留当前 storage 配置不动**。
