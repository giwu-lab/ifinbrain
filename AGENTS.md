# iFinBrain 项目规则（AGENTS.md）

新会话从这里开始。本文件记录项目特有的硬性约束，所有 AI / 自动化工具执行任务前都应先扫一遍。

---

## 1. 国际化（i18n）硬规则

**新增任何面向用户的页面、表单、菜单、提示文案，必须同时更新 3 个地方**：

| 位置 | 内容 |
|---|---|
| `frontend/src/i18n/locales/zh-CN.ts` | 添加中文文案（namespace 与 en-US 对齐）|
| `frontend/src/i18n/locales/en-US.ts` | 添加英文文案 |
| `backend/src/main/resources/i18n/defaults.json` | **必须**运行 `frontend/scripts/i18n-export.mjs` 自动重新生成，**不要**手改 |

**新增 namespace 流程**：
1. 两个 ts 文件都加 namespace（key 结构必须镜像一致）
2. `node frontend/scripts/i18n-export.mjs` 重新生成 defaults.json
3. 如果加了任何 key，`frontend/src/stores/i18n.ts` 里的 `CACHE_KEY` 必须 bump 版本（v6 → v7 → ...）以让前端丢弃旧缓存
4. 菜单 key 命名规则：`menu.<path-segment1>[.<sub-segments>].<component-filename>`；DIR 自身用 `_`（详见 `BasicLayout.vue:menuName()`）
5. 同步通过 `POST /api/system/i18n/sync-defaults` 把 DB 里的 `sys_i18n_message` 表与 defaults.json 对齐

**检查方式**：grep `t('namespace.x')` 用到的 key 在 `defaults.json` 里 100% 存在；不允许 fallback 到 literal key。

---

## 2. 权限（Permission）硬规则

**新功能涉及任何操作必须**：
1. 在 `backend/src/main/resources/db/migration-{dev,prod}/` **同步**新增一个 V{n+1}.sql（两份都要）
3. 同步插入 `sys_permission` (type='API') + `sys_role_perm`（admin 全拿 / operator / viewer 按业务分配）
4. 同步插入 `sys_menu`（顶层 DIR 用 `parent_id=0, type='DIR', component=NULL`；子菜单用 `type='MENU', component='<module>/<view>/Index'`）
5. 前端用 `v-if="auth.hasPerm('<code>')"` 守护每个操作按钮
6. 后端 `@Service` 类用 `perm.require('<code>')` 守护每个公开方法

权限码格式 `<module>:<resource>:<verb>`，如 `data:datasource:update`。

---

## 3. 数据库迁移（Flyway）硬规则

- **新增表 / 新增菜单 / 新增权限必须**走 Flyway，**不要**直接 DDL
- 文件命名 `V{n}__{snake_case}.sql`，n 沿用递增（最近一次是 V10）
- 必须**同时**写两份：`migration-dev/` 和 `migration-prod/`，内容**除注释外完全一致**
- 列定义沿用 SQLite 兼容写法：`id INTEGER PRIMARY KEY AUTOINCREMENT`、`VARCHAR(n) NOT NULL`、`SMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP`
- 软删除**未启用**；删除是真 `DELETE FROM`
- JPA entity 用 `@PrePersist` / `@PreUpdate` 维护 `created_at` / `updated_at`

---

## 4. 加密字段硬规则

任何"密钥 / 密码 / token"字段必须：
1. 数据库列以 `_enc` 后缀（如 `password_enc`、`api_key_enc`）
2. 用 `CryptoUtil.encrypt(plain)` 落库（已有 `backend/.../security/CryptoUtil.java`）
3. API 返回**永远**只回掩码 `********` + `passwordStatus: 'missing' | 'ok' | 'failed'`
4. 前端三态密码组件（与 OCR API Key 一致）：missing → 必填输入框；ok → "已配置 · 修改"；failed → "密钥失效 · 修改"
5. 更新时 `""` / `null` / `"********"` 视为"保留旧值"，**不覆盖**密文

master key 环境变量：`IFINBRAIN_MASTER_KEY`（≥32字节，SHA-256 派生为 AES-256-GCM key）。轮换时已加密字段无法解密 → `passwordStatus='failed'`，用户必须重填。

---

## 5. 后端结构硬规则

- 包根：`com.anomalyco.fapiao.web`，**没有** `*Impl.java`（Service 都是单类）
- Lombok：`@Service @RequiredArgsConstructor @Slf4j`
- 写操作 `@Transactional`，读 `@Transactional(readOnly = true)`
- DTO 三件套：`<Resource>Dto`（响应）/ `<Resource>CreateReq` / `<Resource>UpdateReq`（请求，带 `jakarta.validation.constraints.*`）
- Controller `@RestController @RequestMapping("/api/<module>")`，方法返回 `Result.ok(...)` 公共信封
- 新增 Repository 必须同时改 `FapiaoWebApplication.java` 的 `@EnableJpaRepositories(basePackages = {...})` 列表

---

## 6. 前端结构硬规则

- 路由**完全由 `sys_menu` 驱动**（见 `frontend/src/router/index.ts`）；新增页面**只**需要：
  1. 在 `builtInViews` 加 `'module/view/Index': () => import(...)`
  2. 数据库迁移插入 `sys_menu` 行（component 字符串对应上述 key）
- API 文件：`frontend/src/api/<domain>.ts`，导出 `xxxApi = { list, create, update, remove, ... }`
- 页面：`frontend/src/views/<module>/<view>/Index.vue`，`<script setup lang="ts">`
- 权限：`auth.hasPerm('xxx')`，**不要**自己写 enum
- i18n：`useI18n()` + `t('xxx')`，**不要**写硬编码中文

---

## 7. 设计原则（来自 frontend-design skill）

- **避开 SaaS-card 套娃**：主列表区不强制套 `<el-card class="card-table">`，必要时用 plain 边框 div
- **状态在布局中表达**：左侧竖条 / 行内图标 / 等宽 monospace 连接串，**不滥用** tag/pill
- **测试反馈就地显示**：行内 icon + 文字摘要；**不弹** toast 轰炸
- **数字序 `01/02/03` 仅用于真正的有序步骤**（如 Phase 2 占位页的三步引导），**不要**当装饰
- **不写** `→` 加在按钮后；**不写** ALL-CAPS eyebrow label；**不写** gradient wash
- 弹窗 `label-width="100px"`（label 长的页面如 ocr 用 160px）

### 7.1 `<el-table>` 标准（与发票列表页对齐）

所有列表页的 `<el-table>` **统一以下属性 + 全局样式**，不要在页面里再 inline 覆盖：

```vue
<el-table
  :data="rows"
  v-loading="loading"
  border
  stripe
  size="small"
>
```

**全局样式**（已在 `styles/global.css` 落定，所有页面默认生效）：

| 元素 | 样式 |
|---|---|
| 表头背景 | `var(--color-bg)` |
| 表头颜色 | `var(--color-text)`（强调色，不是 secondary） |
| 表头字重 | `600` |
| 表头字号 | `var(--font-size-sm)` = `13px` |
| 单元格字号 | `var(--font-size-sm)` = `13px` |
| 行底边 | `1px solid var(--color-border-soft)` |
| Hover | `var(--color-primary-soft)` |

**禁止** 在页面里 inline `:header-cell-style` / `:cell-style` 重复声明同一份样式 —— 那是冗余代码，且容易漂移。需要局部变体（如强调某列、固定列着色）才 inline。

### 7.2 页面布局标准（与数据源 / 类目设置页对齐）

所有管理类页面**统一以下骨架**，不要自己造结构：

```vue
<template>
  <div class="page-container fade-in-up {domain}-page">
    <!-- ① 标题行：左标题描述，右主操作按钮（flex space-between） -->
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('xxx.title') }}</h1>
        <p class="page-desc muted">{{ t('xxx.desc') }}</p>
      </div>
      <!-- 主操作按钮：
           - 单按钮：直接子元素
           - 多按钮：<div class="header-actions"> 包裹（否则被 space-between 分散） -->
      <el-button v-if="auth.hasPerm('xxx:create')" type="primary" :icon="Plus" @click="openCreate">
        {{ t('xxx.create') }}
      </el-button>
    </div>

    <!-- ② 列表区：plain 1px 边框容器，避开 SaaS-card 套娃 -->
    <div class="{domain}-table-wrap">
      <el-table :data="rows" v-loading="loading" border stripe size="small">
        <!-- ... 列定义 ... -->
      </el-table>
    </div>
  </div>
</template>

<style scoped>
/* 标题行：与数据源/类目设置页完全一致 */
.{domain}-page .page-header {
  display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 20px;
}
.{domain}-page .page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; }
.{domain}-page .page-desc  { margin: 4px 0 0; font-size: 13px; }

/* 列表区：1px 边框 + bg-elev 背景 + 圆角，与数据源完全一致 */
.{domain}-table-wrap {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-elev);
  overflow: hidden;
}
</style>
```

**强制约定**：

| 项 | 标准 |
|---|---|
| 根 class | `page-container fade-in-up {domain}-page`（`{domain}` 是业务域，如 `ds` / `cat` / `user` / `role`） |
| 标题字号 / 字重 | `22px` / `700` |
| 标题与操作按钮对齐方式 | 标题左、按钮右，**底部对齐**（`align-items: flex-end`） |
| 操作按钮位置 | 单按钮时为 `.page-header` **直接子元素**；多按钮时用 `<div class="header-actions">` 包裹（避免被 space-between 分散到三处） |
| 列表区容器 | 必须有 1px 边框包裹，**禁用 `<el-card>` / `.card-table`** |
| 多按钮 | 标题右侧直接并列，flex gap 用 `style scoped` 控制 |

**禁止**：
- 标题和按钮分两行 / 用 `margin-top` 推下去
- 操作按钮放进任何额外 div 包裹
- 列表区直接裸露不包裹 / 套用 `.card-table`
- 标题字号小于 22px（看起来不像"页面"像"区块"）

**例外**：
- 表单页（创建/详情）不需要表格布局，按 `el-form` + `el-card` 标准即可
- 查询编辑器结果面板（`query/Detail.vue`）保留自有视觉语言

### 7.3 双层吸顶（页头 + 表头）

所有用 `<el-table>` 的列表页，**默认开启**：
- `.page-header` 始终吸在滚动容器顶部（`position: sticky; top: 0; z-index: 30`）
- 表头（`<th>`）紧跟页头下方吸顶（`position: sticky; top: <页头高度>; z-index: 20`）
- 两层都需要 **opaque 背景**（`var(--color-bg)`），否则滚动时下方表格行会穿透

参考值（`docs-page` / `cat-page`）：

```css
.{domain}-page .page-header {
  position: sticky;
  top: 0;
  z-index: 30;
  background: var(--color-bg);
  padding: 12px 0 16px;
  margin-bottom: 0;          /* 让 .table-wrap 的 margin-top 接管间距 */
}
.{domain}-table-wrap {
  margin-top: 16px;          /* 与吸顶的页头之间留 16px 间隔 */
  overflow: visible;         /* 关键：保持默认 visible，让 th sticky 能透到外层滚动容器 */
}
:deep(.{domain}-table-wrap th.el-table__cell) {
  position: sticky;
  top: 92px;                 /* = 页头高度（约 76-80px）+ 16px margin；按实际渲染微调 */
  z-index: 20;
  background: var(--color-bg) !important;
}
```

**关键陷阱**：

| 陷阱 | 原因 | 解决 |
|---|---|---|
| `.table-wrap` 加 `overflow: hidden` | 创建独立 scrollport，th sticky 只在该容器内有效，页面滚动时 th 跟着走 | **不要加**，clip 由 `.el-table` 自身 `border-radius` 保证 |
| `top` 值不对齐页头实际高度 | sticky th 与页头重叠 / 出现空白 | 改完页头 padding 后同步调整 `top`（先用 92px 占位，按实际像素微调） |
| 背景透明 | 滚动时下方表格行文字穿透表头 | `background: var(--color-bg) !important` 强制 opaque |

**禁止**：在 `.table-wrap` 上加 `overflow: hidden` / `overflow: auto` —— 那是 bug 不是优化。

---

## 8. 项目特定技术约束

- Spring Boot **4.1.1** + Java **21**（Spring Boot 4 用 `tools.jackson.databind.ObjectMapper`，**不是** com.fasterxml）
- JDBC 驱动已就位：mysql-connector-j、mssql-jdbc、postgresql（HikariCP 由 spring-boot-starter-data-jpa 间接带入）
- 时区：JVM 默认 `Asia/Shanghai`（`FapiaoWebApplication.main` 强制设置），勿依赖宿主机
- SQLite 默认开发库（`./data/ifinbrain.db`），生产支持 PG / H2；FK CASCADE 需 `PRAGMA foreign_keys = ON`
- HikariDataSource 池：多数据源**自建** `DatasourceConnectionService`（轻量、动态），**不用** dynamic-datasource-spring-boot-starter / AbstractRoutingDataSource
- vue-i18n v11 用 `.` 作路径分隔符；菜单 key 嵌套规则见 §1
- 测试前 `dev.sh restart`；前端 HMR 自动生效；后端 `mvn spring-boot:run`

---

## 9. 默认凭据

- Admin：`admin / Admin@123`
- Operator / Viewer 由 admin 在「用户管理」创建

---

## 10. 不要做的事

- 不要直接 `git commit` / `git push`（除非用户明确要求）
- 不要在 `i18n/defaults.json` 里手改文案（永远走 `i18n-export.mjs` 自动生成）
- 不要写 `*Impl.java` 后缀的 Service
- 不要在迁移里写方言特定语法（保持 SQLite/PG 兼容）
- 不要把硬编码中文 / 英文放进 Vue 模板的 `<template>` 文本节点
- 不要绕过 `PermissionChecker` 直接 @PreAuthorize 字符串权限码

---

## 11. 资源文件同步（最常踩的坑）

Spring Boot 运行时从 **`backend/target/classes/`** 读资源，不读 `src/main/resources/`。
`mvn spring-boot:run` 不会自动复制 `src/main/resources/i18n/defaults.json` 到 target，
每次改了 i18n 文案后必须**显式搬运一次**：

```bash
cp backend/src/main/resources/i18n/defaults.json backend/target/classes/i18n/defaults.json
# 或一次性 process-resources：
cd backend && mvn -q process-resources
```

不搬运的话 `POST /api/system/i18n/sync-defaults` 会读到旧版 defaults.json，前端
i18n store 写入的也是旧文案，新加的 key 永远到不了 DB。同步后端日志会显示
"inserted/updated" 数明显偏少。

i18n 命名空间新增要改两个 `.ts` 文件 + `defaults.json` + i18n cache bump，缺一不可：
- `frontend/src/i18n/locales/zh-CN.ts`
- `frontend/src/i18n/locales/en-US.ts`
- `backend/src/main/resources/i18n/defaults.json`（用 `node frontend/scripts/i18n-export.mjs` 自动生成）
- `backend/target/classes/i18n/defaults.json`（手动 cp，§11）
- `frontend/src/stores/i18n.ts`: `CACHE_KEY` bump（v6 → v7 → v8 → ...）
- `POST /api/system/i18n/sync-defaults`（admin 登录后调用）

### i18n 命名空间命名约定（URL 路径 vs key）

| 类型 | 约定 | 例 |
|---|---|---|
| 页面 content | camelCase（前端代码风格） | `dataMgmt.dsTitle` |
| 菜单 namespace | URL 路径 kebab-case | `/data-mgmt/datasource` → component `data-mgmt/datasource/Index` |
| menu 命名空间 key | **同时**支持 camelCase 和 kebab-case | `menu.dataMgmt.datasource.Index` **和** `menu.data-mgmt.datasource.Index` 都要有 |

原因：`BasicLayout.menuName()` 把 `component` 字段里的 `/` 替换成 `.`，但保持原大小写
（含 `-`），得到 `menu.data-mgmt.datasource.Index`；而 page 里的 i18n key 用 camelCase
`menu.dataMgmt.datasource.Index`。**两组都给 key 才能覆盖所有路径**。

---

## 12. Vite 6 + Monaco Editor 的坑（dev 模式）

**问题**：Vite 6.4.x 没法解析 `import 'monaco-editor/esm/vs/editor/editor.worker?worker'` —— `?worker`
查询后缀在 bare-specifier (从 node_modules) 上失败，报 *"Failed to resolve import ... Does the file exist?"*。
`vite-plugin-monaco-editor` 也不能解决（它走的是 CDN 路线，跟我们 dev/local 部署不匹配）。

**解决**：用 Vite 的 `/@fs/<absolute-path>` filesystem 路由，直接引用 node_modules 里的 worker：

```ts
self.MonacoEnvironment = {
  getWorker: () => new Worker(
    '/@fs/<project>/frontend/node_modules/monaco-editor/esm/vs/editor/editor.worker.js',
    { type: 'module' }
  )
};
```

Vite 会自动把 worker 文件内部的相对导入（`'../internal/common/initialize.js'`）改写成
`'/node_modules/monaco-editor/esm/vs/internal/common/initialize.js?v=xxx'`，浏览器按 Vite 模块图加载即可。

**限制**：这条只对 **dev 模式** 有效（`@fs/` 是 Vite dev server 的特权路由）。生产 build 需要
走 Vite worker plugin 或 esbuild bundling —— 当前项目 dev-only，未做 prod build 配置。

**为什么不用 `public/monaco/` 复制方案**：worker 内部的相对导入路径依赖原 node_modules 树结构，
单独复制 worker 文件会让相对路径（`'../internal/...'）找不到。`@fs/` 保持原路径结构。