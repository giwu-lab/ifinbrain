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
| 3 | `backend/src/main/resources/i18n/defaults.json` | **`node frontend/scripts/i18n-export.mjs` 自动生成**，不要手改 |
| 4 | `frontend/src/stores/i18n.ts` | bump `CACHE_KEY`（v45 → v46 → ...），让前端丢弃旧缓存 |
| 5 | DB `sys_i18n_message` 表 | admin 登录后调 `POST /api/system/i18n/sync-defaults` |

**菜单 key 命名**：`<menu>.<path>.<component>`。`BasicLayout.menuName()` 把 component 里的 `/` 替换为 `.`，得到 `menu.data-mgmt.datasource.Index`（保留大小写含 `-`）；而 page 内 i18n 用 camelCase `menu.dataMgmt.datasource.Index`。**两组都给 key 才能覆盖所有路径**。

**检查方式**：`grep "t('namespace.x')"` 用到的 key 在 `defaults.json` 里 100% 存在；不允许 fallback 到 literal key。

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
- `seedAdminAccess()`：新建/重命名类目时 admin 自动获得 4 个类目 perm（JWT 通过 `auth.refreshTokens()` 重发）
- V23 迁移：现存类目 + 仅 admin seed 类目级 perm
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

---

## 7. 设计原则

- **避开 SaaS-card 套娃**：主列表区不强制套 `<el-card class="card-table">`，用 plain 边框 div
- **状态在布局中表达**：左侧竖条 / 行内图标 / 等宽 monospace 连接串，不滥用 tag/pill
- **测试反馈就地显示**：行内 icon + 文字摘要；不弹 toast 轰炸
- **数字序 `01/02/03` 仅用于真正的有序步骤**，不当装饰
- **不写** `→` 加在按钮后；**不写** ALL-CAPS eyebrow label；**不写** gradient wash
- 弹窗 `label-width="100px"`（label 长的页面如 parser 用 160px）

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
| `documents/category/Index.vue` | 编辑/授权/删除 | 160 |
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

---

## 9. 资源文件同步（i18n + 资源）

Spring Boot 运行时从 `backend/target/classes/` 读资源，不读 `src/main/resources/`。`mvn spring-boot:run` 不会自动复制 `i18n/defaults.json`。**改了 i18n 文案后必须**：

```bash
cp backend/src/main/resources/i18n/defaults.json backend/target/classes/i18n/defaults.json
# 或一次性 process-resources：
cd backend && mvn -q process-resources
```

否则 `POST /api/system/i18n/sync-defaults` 会读到旧版 defaults.json，后端日志显示 "inserted/updated" 数偏少。

---

## 10. 特殊坑（dev 模式）

### 10.1 Vite 6 + Worker（Monaco / PDF.js）

Vite 6.4.x 无法解析 `import 'xxx/worker?worker'`（bare-specifier）。`vite-plugin-monaco-editor` 走 CDN 路线，不适合本地部署。

**统一解决**：worker 文件经 Vite dev server 的 `/@fs/<绝对路径>` filesystem 路由加载。worker 文件**不能**放 `public/`（其内部相对导入路径会断）。

**Monaco 示例**（`data-mgmt/query/Detail.vue:228-234`）：
```ts
self.MonacoEnvironment = {
  getWorker: () => new Worker(
    '/@fs/<project>/frontend/node_modules/monaco-editor/esm/vs/editor/editor.worker.js',
    { type: 'module' }
  )
};
```

**PDF.js 示例**（`components/PdfViewer.vue` + `utils/pdfWorker.ts`）：**用 `fetch` 拿到 worker 文本后，先 `replace()` 剥掉 Vite 在首行注入的 `import { injectQuery as __vite__injectQuery } from "/@vite/client";`，再用 `URL.createObjectURL(new Blob([cleanSource]))` 喂给 Worker**。**不要用 `workerSrc` URL 字符串**——`/@fs/...` 响应里始终带这个首行注入，worker 用 `type:'module'` 加载时会尝试 import `/@vite/client` 失败 → Worker 启动失败 → `getDocument().promise` 永远 hang → canvas 空白。

**关键陷阱**：直接 `fetch('/@fs/.../pdf.worker.min.mjs')` 拿到的字节**也是被注入后的内容**（注入发生在 HTTP 响应阶段，不是 URL 阶段）。所以光 `fetch + blob` 不够，**必须**剥首行：

```ts
const VITE_INJECT_PREFIX =
  /^\s*import\s*\{\s*injectQuery\s+as\s+__vite__injectQuery\s*\}\s*from\s*["']\/@vite\/client["']\s*;?/;
source = source.replace(VITE_INJECT_PREFIX, '');
```

实现见 `frontend/src/utils/pdfWorker.ts`（模块级单例 Promise，`load()` 入口 `await ensurePdfWorkerReady()`，避免 race condition）。

**限制**：当前项目 dev-only（无 prod build 配置）。

### 10.2 其他

- 时区：JVM 默认 `Asia/Shanghai`（`iFinBrainApplication.main` 强制设置），不依赖宿主机
- vue-i18n v11 用 `.` 作路径分隔符
- 多数据源自建 `DatasourceConnectionService`，**不用** `dynamic-datasource-spring-boot-starter` / `AbstractRoutingDataSource`
- JD 驱动：mysql-connector-j、mssql-jdbc、postgresql（HikariCP 由 starter 间接带入）
- SQLite 默认开发库；生产支持 PG / H2；FK CASCADE 需 `PRAGMA foreign_keys = ON`

---

## 11. 不要做的事

- 不要直接 `git commit` / `git push`（除非用户明确要求）
- 不要在 `i18n/defaults.json` 里手改文案（永远走 `i18n-export.mjs` 自动生成）
- 不要写 `*Impl.java` 后缀的 Service
- 不要在迁移里写方言特定语法（保持 SQLite/PG 兼容）
- 不要把硬编码中文 / 英文放进 Vue 模板的 `<template>` 文本节点
- 不要绕过 `PermissionChecker` 直接用 `@PreAuthorize` 字符串权限码
- 不要在 `<el-table>` 操作列里用文字按钮（必须图标 + ···）
- 不要用 `<el-popconfirm>` 做删除确认（统一 `ElMessageBox.confirm`）
- 不要用 axios 接收 blob 响应（已知 bug，会损坏文件）—— 用原生 `fetch().blob()` 或 `arrayBuffer()`
- 不要给所有用户都 broadcast 同一份菜单（每个用户按 perm 过滤；admin 应能看到 Documents DIR 即使没类目）
- **不要在测试结束后把 `storage.type` 切回 `local` 或用 fake AK/SK 覆盖用户密文**——用户的 S3 配置（内网 MinIO 192.168.3.160:9000 / bucket=ifinbrain）是真实测试环境。验证存储相关修复时：用临时 prefix（如 `bucket=ifinbrain-test`）、隔离 AK/SK、或只测 GET/probe 不写回 sys_setting；**除非用户明确要求重置，否则保留当前 storage 配置不动**。
