# 笔记管理（Markdown + 共享 + 团队笔记）设计方案

> 状态：**已实施**（Phase 1–3 全部落地，2026-10-08）
> 验证：后端 `javac` 全量编译通过；前端 `vue-tsc` 新增文件 0 错 + `vite build` 成功
> 落地差异见 §4.2「落地调整」与本文末尾「实施结果」
> 编写时间：2026-10-08
> 适用范围：`/documents/notes`（档案管理 → 笔记管理）

---

## 0. 一页结论

| 决策点 | 结论 | 一句话理由 |
|---|---|---|
| 笔记存在哪 | **DB 表 `sys_note`**（markdown 源码入库），**不**当文件存 storage | 按人/团队授权、标题+正文检索、版本历史这三件事文件存储做不了；「存成 .md 文件」降级为一次「发布到档案库」动作 |
| 编辑器 | **复用已有 Monaco**（5 个 worker 已在 `utils/workers.ts` 配好）+ 自写 markdown 工具条 | 零新增编辑器依赖；第三方 md 编辑器（v-md-editor / bytemd）要适配暗色 token 且与「不引入重 UI 库」风格冲突 |
| 渲染 | 前端 **`marked` + `dompurify`**，后端只存/只返原始 markdown | 服务端渲染 HTML 要防存储型 XSS 且体积翻倍；`dompurify` 已在依赖树里，只需加 `marked` |
| 可见性 | 四档：**PRIVATE / TEAM(按部门) / LIBRARY(按档案库) / ORG(组织内)** | 一套字段表达全部场景，不为每种共享单独建表 |
| 共享名单 | 独立表 `sys_note_share`（人 + READ/WRITE） | 「点名给某人写权限」和「整组可见」是两件事，前者必须能逐人授权 |
| 团队笔记 | TEAM 档 = 同部门可见（`sys_dept` + `sys_user.dept_id` 已存在） | 项目已有部门表和 `todo:task:manage-all` 先例，不再造「团队空间」概念 |
| 并发编辑 | **version 乐观锁 + 409 冲突提示**，不做实时协同（CRDT/OT） | 团队笔记的真实痛点是「互相覆盖」，锁 + 冲突提示能解决；实时协同要引 Yjs/WebSocket，Phase 2 再谈 |
| 删除 | 真 `DELETE FROM`（项目软删除未启用）；「归档」用 `status=0` 表达 | 与 AGENTS §4.1 一致 |
| 权限码 | 新增 `note:view/create/update/delete/share/manage-all/delete-any`；档案库档**复用** `documents:{slug}:*` | 笔记自己的读写/授权必须独立；挂在档案库上的可见性就该吃档案库权限，不重复造 |
| 菜单 | `/documents/notes` 挂 Documents DIR 首位，component `documents/notes/Index` | 笔记是跨档案库能力，置顶比塞到末尾好找 |

**新增依赖：1**（前端 `marked`，~40KB；`dompurify` / `monaco-editor` 已在）。

---

## 1. 范围

### 1.1 做

1. 笔记 CRUD：标题 + markdown 正文 + 标签 + 置顶 + 归档
2. 四档可见性（私有 / 团队 / 档案库 / 组织内）+ 点名共享名单（读 / 写）
3. 列表：分页 + 筛选（我的 / 共享给我的 / 团队 / 档案库 / 已归档 / 标签 / 关键词）
4. 编辑器：Monaco 双栏（源码 | 预览）+ 工具条 + 自动保存（debounce 2s）+ 脏标记
5. 版本历史：每次保存留快照，可查看 / 对比摘要 / 回滚
6. 导出/发布：一键把笔记发布为档案库里的 `.md` 文件（复用现有 storage + `documents:{slug}:upload`）
7. 档案库文件列表页（`documents/list/Index.vue`）加「笔记」抽屉入口：看该库笔记 / 就地新建
8. 关联单个档案文件（`related_document_id`），列表页笔记行可跳回原文件
9. 计数接口（我的 / 共享给我 / 团队），列表页 tab 上做角标

### 1.2 明确不做（避免范围蔓延）

| 不做 | 原因 | 去处 |
|---|---|---|
| 实时协同编辑（CRDT / Yjs / WebSocket 同步） | 要引同步通道 + 冲突合并，v1 的乐观锁已能挡住「互相覆盖」这个主痛点 | Phase 2 |
| 多人同时在线光标 / 正在输入提示 | 依赖上一条 | Phase 2 |
| 笔记模板 / 片段库 | 先把 CRUD 与授权做扎实 | Phase 2 |
| 标签走 `sys_dict` 字典体系 | todo 模块的标签字典化是独立改动；笔记先做自由标签 | Phase 2（对齐 b4b5cba 的做法） |
| 笔记附件上传 | 已有档案上传通道，笔记侧做「链接到档案文件」即可，不重复造存储 | 不做 |
| 服务端 markdown → PDF / HTML 导出 | 要引 openhtmltopdf 之类的渲染器 + 字体，中文字体是坑 | Phase 3 |
| PostgreSQL 全文检索（tsvector + GIN） | v1 关键词走 ILIKE，笔记量级（万级）下够用 | 十万条后 |
| 评论 / @提醒 / 消息通知 | 通知链路项目里还没有 | Phase 3 |
| 独立顶级「协作」模块 | 用户明确要求挂在档案管理下 | 不做 |

---

## 2. 总体架构

```
documents/notes/Index.vue                     笔记管理主页（左列表 / 右阅读编辑区）
  │
  ├─ NoteList.vue          el-table（AGENTS §8 标准）+ 筛选 + tab 角标
  ├─ NoteEditor.vue        标题 + 标签 + 工具条 + Monaco 双栏 + 自动保存
  ├─ NoteViewer.vue        marked + DOMPurify 渲染（只读态 / 大纲目录）
  ├─ ShareDialog.vue       可见性四档 + 点名名单（用户多选）
  ├─ RevisionDrawer.vue    版本历史列表 + 回滚
  └─ api/note.ts           noteApi

documents/list/Index.vue ──「笔记」按钮──▶ NoteDrawer（80% rtl，同预览抽屉规格）

后端 web/documents/note/
  NoteController      /api/notes        ← perm.require('note:*')
  NoteService         ACL 判定 + 乐观锁 + 版本快照
  NoteAclService      canRead / canEdit 判定（唯一权限真相源）
  NoteRepository      Specification 分页（可见性条件下推 SQL）
  NoteShareRepository / NoteRevisionRepository
  dto/                NoteDto / NoteCreateReq / NoteUpdateReq
                      NoteShareDto / NoteShareUpdateReq / NoteRevisionDto
```

---

## 3. 后端设计

### 3.1 新增文件（全部落在 `backend/src/main/java/com/anomalyco/ifinbrain/web/documents/note/`）

```
entity/Note.java            entity/NoteShare.java       entity/NoteRevision.java
repository/NoteRepository.java   NoteShareRepository.java   NoteRevisionRepository.java
service/NoteService.java   service/NoteAclService.java  service/NoteRevisionService.java
controller/NoteController.java
dto/NoteDto.java  dto/NoteCreateReq.java  dto/NoteUpdateReq.java
dto/NoteShareDto.java  dto/NoteShareUpdateReq.java  dto/NoteRevisionDto.java
dto/NoteCandidateDto.java（用户候选项，对齐 TodoAssigneeOption 形状）
```

> 放在 `documents/note/` 而不是新建顶级 `note` 模块：笔记天生绑定档案库，复用 `ArchiveLibraryRepository` 取 slug→perm 前缀，不用跨模块注入。

### 3.2 改动既有文件

| 文件 | 改动 |
|---|---|
| `resources/db/migration-prod/V62__add_note_module.sql` | 新增（见 §4） |
| `iFinBrainApplication.java` | **无需改动**（`@EnableJpaRepositories` 走 basePackages，已含 `web` 整包） |
| `ArchiveLibraryService.seedAdminAccess()` | **无需改动**（笔记权限是全局 `note:*`，不随档案库新建而变） |
| `AuthPrincipal` / JWT | 无改动（`note:*` 走标准 perm 列表下发，`http.ts` 的 403 自动 refresh 已覆盖新权限） |

### 3.3 可见性判定（本功能的核心）

一条笔记 N 对当前用户 U 的读权限，任一命中即可读：

| # | 条件 | 说明 |
|---|---|---|
| 1 | `N.owner_id = U` | 作者本人 |
| 2 | `U ∈ sys_note_share(N)` 且 share 未撤销 | 点名共享；带 grant=WRITE 时同时给编辑权 |
| 3 | `N.scope_type = 'ORG'` 且 U 有 `note:view` | 组织内共享 |
| 4 | `N.scope_type = 'TEAM'` 且 `N.scope_dept_id = U.dept_id` | 同部门可见 |
| 5 | `N.scope_type = 'TEAM'` 且 U 有 `note:manage-all` | 跨部门管理（对齐 `todo:task:manage-all`） |
| 6 | `N.scope_type = 'LIBRARY'` 且 U 有 `documents:{N.category_slug}:view` | 挂在档案库上，吃该库权限 |
| 7 | U 有 `note:manage-all` | 兜底：部门内全部可见 |

写权限（任一命中即可编）：

| # | 条件 |
|---|---|
| 1 | 作者本人 |
| 2 | `sys_note_share(N).grant = 'WRITE'`（无论 scope） |
| 3 | `scope_type = 'TEAM'` 且同部门 |
| 4 | `scope_type = 'LIBRARY'` 且 U 有 `documents:{slug}:update` |
| 5 | U 有 `note:manage-all` |

删除：**仅作者本人**或 `note:delete-any`（只给 admin）。
改可见性 / 改共享名单：需要 `note:share`（作者本人或 `note:manage-all`）。

**实现约束**：`NoteAclService` 是唯一判定入口，`NoteService` 每个读写方法第一行先 `acl.assertCanRead(noteId)` / `acl.assertCanEdit(noteId)`，列表查询则把上述条件翻译成 `Specification` 下推 SQL（**不能**先查一页再在 Java 里 filter —— Todo 模块已经踩过这个坑，见 `TodoTaskService.buildListSpec` 注释）。

### 3.4 并发控制

- `Note.version` 每次保存 `+1`；前端提交时带上打开时拿到的 `version`。
- 不匹配 → `BizException(409, "noteConflict")`，返回当前最新 `version` + 最后修改人。
- 前端收到 409：顶部黄条「张三 刚刚改过这篇笔记」+ 三按钮「查看最新 / 覆盖保存 / 放弃我的修改」，**不静默覆盖**。
- `updated_at` 只做展示，不做判据（毫秒级精度 + 时区不可靠，version 才是判据）。

### 3.5 体积与内容约束

| 项 | 限制 | 理由 |
|---|---|---|
| `title` | ≤ 255 | 与 `sys_document.name` 对齐 |
| `content` | ≤ 1 MB（应用层校验，超了抛 400） | `TEXT` 能存 1GB，但列表页/自动保存都不该被单篇巨型文档拖死 |
| `tags` | ≤ 10 个，单个 ≤ 24 字符 | 防标签体系变成垃圾场 |
| `excerpt` | 列表接口只返前 200 字纯文本 | **content 不进列表响应**，否则一页 20 篇 markdown 就能撑到几 MB（对齐 V61 `error_detail` 存前 N 条的思路） |
| 用户候选 | 只返 `status=1` 的用户（id / username / displayName / deptId） | 对齐 `TodoTaskService.assigneeOptions()` |

### 3.6 接口清单

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/notes` | `note:view` | 分页；`scope` / `keyword` / `tag` / `categoryId` / `onlyArchived` / `onlyPinned` |
| GET | `/api/notes/stats` | `note:view` | tab 角标：mine / shared / team / archived |
| POST | `/api/notes` | `note:create` | 返回 NoteDto |
| GET | `/api/notes/{id}` | ACL assertCanRead | |
| PUT | `/api/notes/{id}` | ACL assertCanEdit | 带 `version`，冲突 409 |
| DELETE | `/api/notes/{id}` | ACL 作者 / `note:delete-any` | 真删，级联删 share + revision |
| PUT | `/api/notes/{id}/pin` | ACL assertCanEdit | `{ pinned }` |
| PUT | `/api/notes/{id}/archive` | ACL assertCanEdit | `{ archived }`（写 `status=0/1`） |
| GET | `/api/notes/{id}/shares` | ACL assertCanRead | |
| PUT | `/api/notes/{id}/shares` | `note:share` | **全量替换**名单（对齐 `ArchiveLibraryController` 的 updatePerms 体例） |
| GET | `/api/notes/{id}/revisions` | ACL assertCanRead | 倒序，不含 content |
| GET | `/api/notes/{id}/revisions/{rev}` | ACL assertCanRead | 含 content |
| POST | `/api/notes/{id}/revisions/{rev}/restore` | ACL assertCanEdit | 回滚成新版本（不删历史） |
| POST | `/api/notes/{id}/publish` | `documents:{slug}:upload` | `{ categoryId, folderPath? }` → 写成 `.md` 文件 |
| GET | `/api/notes/candidates` | `note:share` | 用户候选项（复用 `userRepo.findAllByStatusOrderByUsernameAsc`） |
| GET | `/api/notes/libraries` | `note:view` | 可关联的档案库（只返 U 有 view 的） |

### 3.7 版本快照策略

- 每次 `PUT /{id}` 成功后，往 `sys_note_revision` 写一条**保存前**的快照（title + content + editor）。
- 保留最近 **50** 条/篇（`NoteRevisionService` 里按 revision desc 裁剪，避免长笔记无限膨胀）。
- 回滚 = 把旧内容当作一次普通保存提交（走同一套乐观锁），历史不丢。

---

## 4. 数据库迁移

### 4.1 放哪个目录（重要，先澄清规则冲突）

`FlywayConfig` **同时**加载 `db/migration` 和 `db/migration-prod`：

```java
locations.add("classpath:db/migration");
locations.add("classpath:db/migration-prod");
```

Flyway 会把两个目录的脚本**合并**校验版本号。同一版本号出现在两个 location 会直接报 `Found more than one migration with version 62`。而 `db/migration/` 实际只有 V2 / V8 / V9 三个历史脚本，V54–V61 **全部只在 `migration-prod/`**。

所以：**V62 只写进 `db/migration-prod/`**，这是 V54 以来的一致做法。

> AGENTS.md §3「两份都要」与 §4.1「`db/migration`（跨库共用）+ `db/migration-prod`（PG 方言）」的表述已过时（那是 SQLite 时代的残留，实际 SQLite 目录已删）。建议本次一并把 AGENTS.md 这两行改成「新迁移只写 `db/migration-prod/`；`db/migration/` 是 V2–V9 历史冻结目录，不再新增」。

### 4.2 DDL

```sql
-- V62: 笔记管理模块（档案管理 → 笔记管理）

CREATE TABLE IF NOT EXISTS sys_note (
    id              BIGSERIAL     PRIMARY KEY,
    title           VARCHAR(255)  NOT NULL,
    content         TEXT          NOT NULL DEFAULT '',
    -- PERSONAL=仅自己 TEAM=同部门 LIBRARY=挂档案库 ORG=组织内
    scope_type      VARCHAR(16)   NOT NULL DEFAULT 'PERSONAL',
    scope_dept_id   BIGINT,                 -- scope_type=TEAM 时必填
    category_id     BIGINT,                 -- scope_type=LIBRARY 时必填（FK 见下）
    tags            TEXT[]        NOT NULL DEFAULT '{}',
    pinned          SMALLINT      NOT NULL DEFAULT 0,
    -- 1=ACTIVE 0=ARCHIVED（软删除未启用，「归档」是业务状态不是删除）
    status          SMALLINT      NOT NULL DEFAULT 1,
    related_document_id BIGINT,             -- 关联单个档案文件（可空）
    owner_id        BIGINT        NOT NULL,
    owner_name      VARCHAR(64),            -- 冗余快照，避免每行 join sys_user
    last_editor_id  BIGINT,
    last_editor_name VARCHAR(64),
    version         INTEGER       NOT NULL DEFAULT 0,   -- 乐观锁
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- 约束集中在 DB 层，避免服务层漏判
    CONSTRAINT ck_note_scope CHECK (
        (scope_type = 'TEAM'    AND scope_dept_id IS NOT NULL) OR
        (scope_type = 'LIBRARY' AND category_id   IS NOT NULL) OR
        scope_type IN ('PERSONAL', 'ORG')
    )
);
CREATE INDEX IF NOT EXISTS idx_note_owner   ON sys_note (owner_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_note_scope   ON sys_note (scope_type, scope_dept_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_note_lib     ON sys_note (category_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_note_status  ON sys_note (status, pinned, updated_at DESC);

ALTER TABLE sys_note
    ADD CONSTRAINT fk_note_category FOREIGN KEY (category_id)
        REFERENCES sys_document_category (id) ON DELETE CASCADE;
```

> `ON DELETE CASCADE`：档案库被删时它的库级笔记一起走。对齐 V58 `document_category_fk_cascade` 的既有决定。
>
> **落地调整（V62 实际写法）**：
> - `tags` 用 `TEXT`（首尾逗号包裹的规范化字符串）而非 `TEXT[]`——项目里没有任何 entity 映射过
>   PG 数组（`SysMenu.config_json` / `SysSetting.value_text` 都走 `columnDefinition="TEXT"`），
>   首次引入数组映射一旦配错就是**启动期**炸，删掉 GIN 索引换 `LIKE '%,tag,%'` 精确匹配更划算。
> - 外键与 CHECK 约束改用 `DO $$ ... IF NOT EXISTS (SELECT 1 FROM pg_constraint ...) $$` 包裹，
>   因为 PG 的 `ALTER TABLE ADD CONSTRAINT` 没有 `IF NOT EXISTS`，而整个迁移要求幂等。

```sql
CREATE TABLE IF NOT EXISTS sys_note_share (
    id          BIGSERIAL   PRIMARY KEY,
    note_id     BIGINT      NOT NULL REFERENCES sys_note (id) ON DELETE CASCADE,
    user_id     BIGINT      NOT NULL,
    grant_type  VARCHAR(8)  NOT NULL DEFAULT 'READ',   -- READ / WRITE
    created_by  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_note_share UNIQUE (note_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_note_share_user ON sys_note_share (user_id);

CREATE TABLE IF NOT EXISTS sys_note_revision (
    id          BIGSERIAL   PRIMARY KEY,
    note_id     BIGINT      NOT NULL REFERENCES sys_note (id) ON DELETE CASCADE,
    revision    INTEGER     NOT NULL,
    title       VARCHAR(255) NOT NULL,
    content     TEXT        NOT NULL,
    editor_id   BIGINT,
    editor_name VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_note_revision UNIQUE (note_id, revision)
);
CREATE INDEX IF NOT EXISTS idx_note_revision_note ON sys_note_revision (note_id, revision DESC);
```

### 4.3 权限 / 角色 / 菜单（同一份 V62 内，NOT EXISTS 幂等，风格对齐 V61）

| 权限码 | 含义 |
|---|---|
| `note:view` | 进入笔记模块、读（可见性判定后） |
| `note:create` | 新建笔记、归档、改标签 |
| `note:update` | 编辑有权笔记 |
| `note:delete` | 删除**自己**的笔记 |
| `note:delete-any` | 删除**任何人**的笔记（审计兜底，只给 admin） |
| `note:share` | 改可见性 / 维护共享名单 |
| `note:manage-all` | 部门内管理他人笔记（对齐 `todo:task:manage-all`） |

角色分配：

| 角色 | 权限 |
|---|---|
| admin | 全部 7 个 |
| operator | view / create / update / delete / share / manage-all |
| viewer | 仅 view（能读团队笔记，不能写） |

菜单：

```sql
-- 笔记管理置顶：sort=0，同时把原 /documents/archive（sort=0）挪到 1，避免同 sort 靠 id 排序产生歧义
INSERT INTO sys_menu (parent_id, name, path, component, icon, sort, permission, type, status)
SELECT d.id, 'Notes', '/documents/notes', 'documents/notes/Index', 'Document', 0,
       'note:view', 'MENU', 1
  FROM sys_menu d
 WHERE d.path = '/documents' AND d.type = 'DIR'
   AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.path = '/documents/notes');

-- （落地时改为 sort = -1：负数保证与现有 0/1 不撞，也就无需再动 /documents/archive 的 sort）
```

> 迁移前先跑一次 `SELECT path, sort FROM sys_menu WHERE parent_id = (SELECT id FROM sys_menu WHERE path='/documents') ORDER BY sort;` 确认没有别的 0/1 占用；若有冲突改成空闲值。

---

## 5. 前端设计

### 5.1 新增文件

| 文件 | 职责 |
|---|---|
| `src/api/note.ts` | `noteApi`（含 `NoteItem` / `NoteShare` / `NoteRevision` 类型），形状对齐 `api/document.ts` |
| `src/views/documents/notes/Index.vue` | 主页：`page-container fade-in-up note-page` + 左列表右编辑 |
| `src/views/documents/notes/components/NoteList.vue` | 列表 + 筛选 tab + 行内操作列（§8.2 图标直显 + `···` 删） |
| `src/views/documents/notes/components/NoteEditor.vue` | 标题 / 标签 / 可见性入口 / 工具条 / Monaco 双栏 / 自动保存 |
| `src/views/documents/notes/components/NoteViewer.vue` | `marked` + `DOMPurify` 只读渲染 + 标题大纲 |
| `src/views/documents/notes/components/ShareDialog.vue` | 四档可见性 + 点名名单（`el-transfer` 或多选） |
| `src/views/documents/notes/components/RevisionDrawer.vue` | 版本历史 + 回滚 |
| `src/views/documents/notes/components/NoteDrawer.vue` | 挂在 `documents/list/Index.vue` 上的库内笔记抽屉（80% rtl） |
| `src/utils/markdown.ts` | `renderMarkdown(src)`：`marked.parse` + `DOMPurify.sanitize`（白名单收紧），全局单点 |

### 5.2 改动

| 文件 | 改动 |
|---|---|
| `src/router/index.ts` | `builtInViews` 加 `'documents/notes/Index': () => import('@/views/documents/notes/Index.vue')` |
| `src/views/documents/list/Index.vue` | header 加「笔记」按钮（`v-if="auth.hasPerm('note:view')"`，无 category 时隐藏），挂 `NoteDrawer` |
| `src/utils/workers.ts` | 抽出统一的 Monaco environment 初始化（`query/Detail.vue` 里那 7 行搬过来共用），笔记编辑器直接引 |
| `package.json` | 加 `"marked": "^16.x"`（唯一新依赖） |
| `src/i18n/locales/zh-CN.ts` / `en-US.ts` | 新增 `note.*` 命名空间（预计 ~60 keys），**两份都要**（§2） |

### 5.3 编辑器选型对比

| 方案 | 结论 | 理由 |
|---|---|---|
| **Monaco + marked（推荐）** | ✅ | 编辑器已装、worker 已配（AGENTS §10.1）、暗色靠 `--color-*` token 自动适配；`marked` 只负责预览 |
| `@kangc/v-md-editor` | ❌ | 主题要重写一遍才能过 §7.3 暗色 14 组件覆盖；引入一整套预置样式 |
| `bytemd` | ❌ | 依赖 gfm 插件包，Vite 下 ESM 兼容性要验证；收益不足 |
| `@file-viewer/renderer-text` 的 md | 只读可用 | 能复用「只读渲染」，但不能编辑 —— 折中方案：预览区仍走自己的 `markdown.ts`，保证样式与正文页一致 |

### 5.4 自动保存策略

- 打开笔记 → `GET /{id}` 拿 `content` + `version`；进入编辑态才起自动保存。
- 输入后 **debounce 2s** 发 `PUT /{id}`（带 `version`）；编辑区右上角显示「已保存 / 保存中 / 有未保存修改」。
- 页面切换 / 关闭 tab 前：若有脏数据，`onBeforeRouteLeave` 拦截提示（`tab.ts` 的多标签页会让离开变得常见）。
- 409 → 停止自动保存，转冲突态，黄条提示直到用户选择处理方式。
- 失败（非 409）→ 保留脏数据 + 行内红字，不弹 toast 轰炸（§7 设计原则）。

### 5.5 暗色合规

- 预览容器、代码块、表格边框一律用 `--color-bg-elev` / `--color-border` / `--color-border-soft`，**禁止** `background: white`（§7.3.6）。
- Monaco `theme` 跟随 `stores/theme.ts` 的 `mode`，切主题时 `monaco.editor.setTheme()`；两套主题用 `vs` + `vs-dark` 基底再覆盖 token 色。
- 代码高亮：先用 `--color-text-secondary` + 等宽字体，不引 shiki/highlight.js（依赖预算）。
- 完成后跑 `node scripts/dark-theme-shoot.mjs`，把笔记页加进截图清单。

---

## 6. 实施阶段

| 阶段 | 内容 | 产出 |
|---|---|---|
| **Phase 1**（建议先落地） | V62 迁移 + entity/repository/service/controller + 私有笔记 CRUD + Monaco 双栏编辑 + 列表页 | 单人笔记闭环可用 |
| **Phase 2** | 四档可见性 + `sys_note_share` + 共享对话框 + `NoteAclService` + 部门联动 + admin manage-all | 共享与团队笔记能力（本次需求主体） |
| **Phase 3** | 版本历史 + 回滚 + 发布到档案库（`.md`） + 档案库页笔记抽屉 + 409 冲突 UI + 统计角标 | 协作闭环 |
| **Phase 4**（可选） | 标签字典化 / PG 全文检索 / 笔记模板 / 实时协同 | — |

> Phase 2 才是「共享 + 团队笔记」的主体。如果要一次交付完，Phase 1+2 是一个可验收的最小集；Phase 3 建议单独立一轮。

---

## 7. 风险清单

| 风险 | 影响 | 应对 |
|---|---|---|
| 可见性条件下推 SQL 写错 → 越权读到别人的笔记 | 高（数据泄露） | 判定只写在 `NoteAclService` 一处；列表查询的 Specification 必须与单条 assertCanRead **同一套谓词**，单测覆盖四种 scope × 四种身份矩阵 |
| 前端自动保存打爆后端 / 覆盖同事内容 | 中 | debounce 2s + version 乐观锁 + 409 冲突态；不做「自动重试覆盖」 |
| markdown 内容撑大列表接口 | 中 | 列表只返 `excerpt`（200 字纯文本），content 仅详情接口返回 |
| XSS（笔记里贴 `<script>` / `javascript:` 链接） | 高 | 渲染只在前端且必须过 `DOMPurify`（禁用 `javascript:`、事件属性、`style`）；后端永不返回渲染后 HTML |
| `monaco` worker 在笔记页重复初始化 | 低 | 抽 `utils/monaco.ts` 单点初始化，复用 `utils/workers.ts` 的 URL 解析 |
| 同一版本号写进两个 Flyway 目录 | 启动即失败 | V62 只进 `db/migration-prod/`（§4.1） |
| 菜单 sort 与现有 `/documents/archive` 撞值 | 低 | 迁移里同时把 archive 挪到 1；上线前查一次现网 sort |
| 自动保存与 `keep-alive` 缓存叠加导致草稿丢失 | 中 | `onBeforeRouteLeave` 脏数据拦截 + `onDeactivated` 立即 flush 一次 |

---

## 8. 待确认（已给默认值，认可即可开工）

| # | 问题 | 默认值 |
|---|---|---|
| 1 | 「团队」= 部门（`sys_dept`）还是自由建协作组？ | **部门**（数据与权限都已存在；自由组要新建成员表和成员管理 UI） |
| 2 | 组织内共享（ORG）默认只读对吗？ | **只读**，编辑需点名 WRITE 或 `note:manage-all` |
| 3 | 档案库档笔记的编辑权是否等同档案库 `documents:{slug}:upload`？ | **是**（该库能传文件就能改笔记，不再单开权限位） |
| 4 | 版本历史保留条数 | **50 条/篇** |
| 5 | 单篇正文上限 | **1 MB** |
| 6 | 是否本次就做「发布为档案文件」？ | **Phase 3**，先不做 |
| 7 | 笔记菜单置顶还是置末？ | **置顶**（跨库能力，比单库入口更常用） |
| 8 | `sys_note` 表名是否带 `sys_` 前缀？ | **带**（与 `sys_todo_task` / `sys_import_log` 保持一致） |
| 9 | 是否同步修正 AGENTS.md 的 Flyway 目录表述？ | **建议改**（§4.1 已与实际不符） |
---

## 9. 实施结果（2026-10-08 落地）

### 9.1 顺带修掉的问题

| 问题 | 处理 |
|---|---|
| `AGENTS.md` §3「两份都要」+ §4.1「跨库共用目录」与实际不符 | 改为「新迁移只写 `db/migration-prod/`；`db/migration/` 是 V2–V9 历史冻结目录」；§7.3 表格里的 `migration-{dev,prod}/V39` 一并改成 `migration-prod/` |
| `query/Detail.vue` 里 MonacoEnvironment 是内联的 | 抽到 `utils/monaco.ts`（`setupMonacoEnv` 幂等 + `defineAppTheme` 跟随主题），笔记编辑器与查询编辑器共用 |

### 9.2 新增文件清单

**后端**（`backend/src/main/java/com/anomalyco/ifinbrain/web/documents/note/`）
`entity/{Note,NoteShare,NoteRevision}` · `repository/{NoteRepository,NoteShareRepository,NoteRevisionRepository}` ·
`dto/{NoteDto,NoteCreateReq,NoteUpdateReq,NoteShareDto,NoteShareUpdateReq,NoteRevisionDto,NoteCandidateDto,NoteStatsDto,NotePublishReq}` ·
`service/{NoteAclService,NoteRevisionService,NoteService}` · `controller/NoteController`

**迁移**：`resources/db/migration-prod/V62__add_note_module.sql`

**前端**
`src/api/note.ts` · `src/utils/markdown.ts` · `src/utils/monaco.ts` ·
`src/views/documents/notes/Index.vue`（**列表页**）· `src/views/documents/notes/Edit.vue`（**新建/编辑页**）·
`components/{NoteList,NoteEditor,NoteViewer,ShareDialog,RevisionDrawer,NoteDrawer}.vue`

**改动**：`AGENTS.md` · `frontend/package.json`（+`marked`） · `frontend/src/router/index.ts`（builtInViews） ·
`frontend/src/i18n/locales/{zh-CN,en-US}.ts`（`note.*` 约 100 keys） ·
`frontend/src/views/documents/list/Index.vue`（笔记按钮 + 抽屉） ·
`backend/.../documents/service/DocumentService.java`（+`publishText` / +`findNames`）

### 9.3 与方案的三处偏离

1. **`tags` 列从 `TEXT[]` 改成 `TEXT`**（首尾逗号包裹），理由见 §4.2。
2. **菜单 `sort = -1`** 而不是「置顶 0 + 把 archive 挪到 1」——负数天然不撞现有 0/1，
   少一次对既有行的 UPDATE，迁移更干净。
3. **抽屉里的部门候选从候选用户反推**（`candidates` 里按 `deptId` 去重），
   没有为笔记页新增部门树接口——省一个端点，代价是「没有成员的用户不会出现在候选里」。

### 9.3.1 路由拆分（2026-10-08 追加）

初版做成了「列表 + 编辑器同屏分栏」。实测后按 `data-mgmt/query` 的既有做法改成**独立页面**：
编辑器要占满宽度，和列表挤一屏时右侧 markdown 预览被压到没法用。

| 路由 | 组件 | 说明 |
|---|---|---|
| `/documents/notes` | `notes/Index.vue` | 列表页：tab 筛选 / 搜索 / 置顶 / 归档 / 删除 |
| `/documents/notes/new` | `notes/Edit.vue` | 新建。**不自动保存**（还没 id，PUT 无处可发），点「保存」才 POST，成功后 `replace` 到真实 id |
| `/documents/notes/:id(\d+)` | `notes/Edit.vue` | 编辑。输入停 2s 自动保存，version 乐观锁 + 冲突条 |

配套改动：
- 三条路由**声明在 `/documents/:slug` 之前**（虽然 vue-router 按 specificity 匹配，静态段本就赢，但显式前置避免以后调整顺序时静默失效）
- `buildRoutesFromMenus` 加 `documents/notes/Index` 的 skip（与 `documents/archive/Index` 同理由）
- `meta.component` 统一指 `documents/notes/Index` → tab 名走 `menu.documents.notes.Index`（新建/编辑两个 tab 同名，由 URL 后缀区分，与 `/design/form` 一致）
- 补了漏掉的 i18n key `menu.documents.notes._` / `.Index` —— 之前侧边栏会显示字面量 key（违反 AGENTS §2）
- 档案库文件列表页的「笔记」抽屉 → 新建时带 `?categoryId=`，编辑页据此把草稿预设为「挂该档案库」
### 9.4 未做（按 §1.2 明确不做）

实时协同 / 模板库 / 标签字典化 / 附件上传 / 服务端导出 PDF / PG 全文检索 / 评论提醒。

### 9.5 遗留验证项（本机无法覆盖）

- 本机**没有 Maven**（`mvn` 不在 PATH，也没有 wrapper），后端是用 `~/.m2` 里的依赖
  `javac` 全量编译验证的 —— 只证明**能编译**，不证明 `mvn package` + 启动 + Flyway 实跑通过。
  上线前请跑一次 `./scripts/dev.sh restart`，重点看 **V62 是否成功应用**
  （CHECK 约束与 `fk_note_category` 若与现存数据冲突会在启动期报错）。
- ACL 的四档可见性 × 四种身份矩阵**没有单测覆盖**（项目当前没有 `src/test` 基建）。
  §7 风险表里列的越权风险，建议上线后按那个矩阵手工过一遍：
  用 viewer 账号直接访问他人 PRIVATE 笔记的 `GET /api/notes/{id}`，应得 404 而非 200。