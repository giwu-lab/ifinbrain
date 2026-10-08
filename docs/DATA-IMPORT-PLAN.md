# 数据导入（Excel / CSV → 外部数据源表）设计方案

> 状态：方案待评审，未开始编码
> 编写时间：2026-10-08
> 适用范围：`/data-mgmt/import`（数据管理 → 数据导入）

---

## 0. 一页结论

| 决策点 | 结论 | 一句话理由 |
|---|---|---|
| 解析在哪端 | **后端 POI**（前端 SheetJS 只做导出） | 导入必须以**目标列元数据**为准做类型转换，且要后端批量 INSERT；前端解析会把整表转成几百 MB JSON |
| 目标库范围 | **v1 只支持外部数据源**（`sys_datasource` 里的 mysql / mssql / postgresql） | 本项目主库 PG 的表由 Flyway + JPA 管，导 Excel 行会绕过全部业务校验 |
| 注入防线 | 标识符**只从 `information_schema` 白名单取**，按方言加引号 | 表名/列名是本功能唯一无法参数化的注入面 |
| 权限模型 | 新增 `data:import:view / :execute / :log:delete`，**不复用** `data:datasource:*` | 「往生产库写数据」和「配置数据源」是两件事，权限必须正交 |
| 交互形态 | 4 步向导：选源 → 上传 → 映射 → 预检+执行 | 导入失败的主要原因是映射错，必须有预检这一步拦在写库之前 |
| 默认事务 | 单事务原子提交，每 1000 行 `executeBatch` | 「导了一半」比「导失败」更难排查 |
| 审计 | 新增 `sys_import_log`，页面带「导入历史」tab | 「谁往这张表写了 3 万行」必须能回答 |

**新增依赖：0**（POI 已在 pom 里用于导出；Hikari / information_schema 元数据链路已具备）。

---

## 1. 范围

### 1.1 做

1. 选外部数据源 → 选目标表（复用 `GET /datasources/{id}/schema`）
2. 上传 `.xlsx / .xls / .csv`，可选 sheet、CSV 编码、是否含表头
3. 表头 → 目标列**自动映射 + 手工调整**（同名自动配对）
4. **预检（dry-run）**：类型转换错误、必填缺失、超长、目标表当前行数、预计影响 —— 不写库
5. 执行导入，返回成功/失败/跳过行数 + 错误明细（行号 / 字段 / 原始值 / 原因）
6. 导入历史列表 + 详情 + 错误明细
7. **导入模板下载**（按目标列生成 xlsx 表头 + 示例行）

### 1.2 明确不做（避免范围蔓延）

| 不做 | 原因 |
|---|---|
| 导入到本项目主库 PG（`sys_*` / `invoice_*` / `todo_*`） | 那些表有业务校验（发票金额、归属人、审计字段 `@PrePersist`），表级导入会全绕过。要支持得做「业务级导入」，是另一个需求 |
| 自动建表 / 改表结构 | 先在外部库建好表。`Import` 只写数据，不碰 DDL |
| Upsert（按主键/唯一键更新） | 需要唯一键 + `ON DUPLICATE`/`MERGE` 方言分支，放 Phase 2 |
| 字段级转换表达式（`{col} + 1`） | 放 Phase 2，且要走表达式沙箱，不能裸求值 |
| 子表 / 多表关联导入 | 一期只做单表 |
| 定时导入 / 订阅目录自动同步 | 放 Phase 3 |
| 分布式任务 / 进度条推送（SSE） | 单次 ≤ 5 万行，POI 流式解析 + 同步返回即可，不值得引入异步框架 |

---

## 2. 总体架构

```
Index.vue (4 步向导 + 历史 tab)
  │
  ├─ Step 1 选源：GET  /datamgmt/datasources            (已有)
  │           GET  /datamgmt/datasources/{id}/schema   (已有，需补列长度/精度)
  │           GET  /datamgmt/imports/template?ds&table  (新：下 xlsx 模板)
  │
  ├─ Step 2 上传：POST /datamgmt/imports/preview   multipart(file, dsId, tableRef, sheet, hasHeader, encoding)
  │                       └─ ImportPreviewDto { sessionId, sheetNames[], headers[], sampleRows[][], totalRows, warnings[] }
  │                          解析在 ImportFileReader（POI 流式）
  │                          结果入 ImportSessionService（内存 + TTL）
  │
  ├─ Step 3 映射：POST /datamgmt/imports/validate  { sessionId, mappings[], options }
  │                       └─ ImportValidateDto { totalRows, errorRows[≤200], warnings[] }   ← dry-run，不写库
  │
  └─ Step 4 执行：POST /datamgmt/imports/commit   { sessionId }
                    └─ ImportResultDto { logId, inserted, failed, durationMs }
                       ImportExecutorService → ImportTargetResolver（标识符白名单）
                                         → ImportTypeConverter
                                         → batch INSERT（单事务）
                       结果落 sys_import_log

GET  /datamgmt/imports?page&size     导入历史
GET  /datamgmt/imports/{logId}        历史详情（含错误明细）
DELETE /datamgmt/imports/{logId}     删日志
DELETE /datamgmt/imports/sessions/{sessionId}   丢弃预检会话（用户改配置时前端主动清）
```

**为什么用 sessionId 而不是在 commit 时重新上传**：预检和执行必须基于**同一份解析结果**，否则用户在两步之间改了文件/编码，预检结论就作废。session 把「解析一次、映射多次、执行一次」固化下来。

---

## 3. 后端设计

### 3.1 新增文件

```
datamgmt/
├── controller/ImportController.java              @RequestMapping("/api/datamgmt/imports")
├── dto/
│   ├── ImportPreviewDto.java        { sessionId, sheetNames[], sheet, headers[], sampleRows[][], totalRows, warnings[] }
│   ├── ImportMappingReq.java        { sessionId, mappings[{sourceHeader, targetColumn, enabled}], options{ mode, skipEmptyRow, continueOnError, batchSize } }
│   ├── ImportValidateDto.java       { valid, totalRows, wouldInsert, errorRows[ImportErrorRow], warnings[] }
│   ├── ImportErrorRow.java          { rowNo, sheet, field, rawValue, reason }
│   ├── ImportCommitResultDto.java   { logId, totalRows, inserted, failed, skipped, truncated, durationMs }
│   ├── ImportLogDto.java            历史行
│   └── ImportOptions.java           嵌套 record（mode / skipEmptyRow / continueOnError / batchSize）
├── entity/SysImportLog.java
├── repository/SysImportLogRepository.java
├── service/
│   ├── ImportFileReader.java        POI 流式读 xlsx/xls + CSV（编码探测），产出 List<List<String>>
│   ├── ImportTypeConverter.java     String → number/date/datetime/boolean/null，含格式清洗
│   ├── ImportTargetResolver.java    元数据白名单 + 方言引号 + 系统 schema 黑名单
│   ├── ImportSessionService.java    session 缓存 + TTL + 容量闸门
│   ├── ImportExecutorService.java   预检 + 批量写入 + 错误收集
│   └── ImportLogService.java        历史 CRUD + 错误明细 CSV
└── common/ImportSafetyChecker.java  标识符校验（不复用 SqlSafetyChecker）
```

### 3.2 改动既有文件

| 文件 | 改动 | 为什么 |
|---|---|---|
| `DatasourceSchemaDto.ColumnSchema` | 加 `maxLength` / `precision` / `scale` / `isNullable`（已有） | 类型转换需要知道 varchar(50) 与 varchar(200) 的差别，否则超长只能等 DB 报错 |
| `DatasourceSchemaService` | 三处 `information_schema` 查询各加 2 列（`character_maximum_length`, `numeric_precision`, `numeric_scale`） | 同上 |
| `DatasourceService` | 新增 `loadDecrypted(Long id, String permCode)` 重载；原 `loadDecrypted(id)` 委托为 `loadDecrypted(id, "data:datasource:test")` | 导入服务要解密密码，但**不该**要求 `data:datasource:test` 权限（见 §3.3） |
| `application.yml` | 新增 `ifinbrain.import.*` | 限额集中可调 |

> **顺手发现的既有问题**（不在本次范围，但会影响导入页）：
> `GET /datasources/{id}/schema` 内部走 `loadDecrypted` → 要求 `data:datasource:test`。
> 按 V10，viewer 只有 `view`，所以 **viewer 调 schema 接口会 403**。
> 建议单独一条迁移把 `DatasourceSchemaService` 改用 `data:datasource:view`。

### 3.3 权限矩阵

| 接口 | 权限码 | admin | operator | viewer |
|---|---|---|---|---|
| `GET /imports`（历史） | `data:import:view` | ✅ | ✅ | ✅ |
| `POST /imports/preview` | `data:import:view` | ✅ | ✅ | ✅ |
| `POST /imports/validate` | `data:import:view` | ✅ | ✅ | ✅ |
| `GET /imports/template` | `data:import:view` | ✅ | ✅ | ✅ |
| `GET /imports/{logId}` | `data:import:view` | ✅ | ✅ | ✅ |
| **`POST /imports/commit`** | **`data:import:execute`** | ✅ | ✅ | ❌ |
| `DELETE /imports/{logId}` | `data:import:log:delete` | ✅ | ❌ | ❌ |

`operator` 拿到 execute 的理由：数据录入是运营的日常动作；不给 `log:delete` 是因为日志是审计痕迹。
**truncate 模式额外要求 `data:datasource:update`**（它等价于「清空一张业务表」，比普通导入重）。

### 3.4 安全设计（本功能的核心）

SQL 注入面只有一处：**表名 / schema 名 / 列名**（值全部走 `?` 绑定，没有第二个注入面）。防线四层：

1. **值**：全部 `PreparedStatement.setObject` 绑定，永不拼字符串（沿用 `QueryExecutor` 的做法）。
2. **标识符白名单**：`ImportTargetResolver.resolve(dsId, schema, table)` 在**执行时重新查一次** `information_schema.columns`（不复用前端传来的列名），只接受元数据里完全匹配的 schema/table/column。
3. **格式双保险**：标识符必须匹配 `^[A-Za-z_][A-Za-z0-9_$-]{0,62}$`，且按方言加引号（PG `"x"`、MySQL `` `x` ``、MSSQL `[x]`）。
4. **黑名单**：拒绝 `pg_catalog` / `information_schema` / `sys` / `mysql` / `performance_schema` / `sysdbs` 等系统 schema。

**明确不复用 `SqlSafetyChecker`**：它的存在意义就是「查询链路只允许 SELECT」，为了导入去加白名单开关会让那条防线整体失去意义。导入另起 `ImportSafetyChecker`，职责单一：只校验标识符与 schema 黑名单。

**导入侧的一个隐蔽风险**：Excel 里以 `=` / `+` / `-` / `@` 开头的文本单元格，POI 读公式单元格时应取 **cached value** 而不是公式文本（`CellType.FORMULA` → `getCachedFormulaResultType()`），否则「`=1+1`」会作为字符串写进库，被下游系统当公式执行。（导出侧项目已有 CSV formula injection 防护，导入侧这是镜像问题。）

### 3.5 类型转换

`ImportTypeConverter` 按目标列的 `type` 分派（`DatasourceSchemaService.guessType` 的 5 类）：

| 目标类型 | 接受 | 清洗规则 |
|---|---|---|
| `number` | `1234.5` / `1,234.56` | 去千分位逗号 → `BigDecimal`；空 → null |
| `date` | `2024-01-31` / `2024/1/31` / `20240131` / Excel 序列号 | → `LocalDate` |
| `datetime` | 上述 + `T`/空格分隔的时间部分 | → `LocalDateTime` |
| `boolean` | `true/false`、`1/0`、`是/否`、`Y/N`、`TRUE/FALSE` | → `Boolean` |
| `string` | 原样 | `trim` + 全角空格 `\u3000` → 半角；`maxLength` 超长 → **截断并记 warning**（可关） |

失败 → 该单元格记一条 `ImportErrorRow{rowNo, field, rawValue, reason}`，不中断其它行。

**不做**的事：不猜「`12%`」是不是 0.12（歧义大），遇到只记 warning 让用户自己改；不做大小写/全半角自动纠正以外的模糊匹配。

### 3.6 导入模式

| mode | 行为 | 权限 | 阶段 |
|---|---|---|---|
| `append` | `INSERT INTO t (cols) VALUES (?,..)` | `data:import:execute` | Phase 1 |
| `truncate` | 先 `DELETE FROM t`（PG 可用 `TRUNCATE`）再 append | + `data:datasource:update`，二次确认弹窗必须输入表名 | Phase 2 |
| `upsert` | 按主键/唯一键更新或插入 | + `data:datasource:update` | Phase 3 |

### 3.7 限额与配置

```yaml
ifinbrain:
  import:
    enabled: true
    max-file-size: 20MB      # 超出直接 400，提示拆分
    max-rows: 50000          # 超出直接拒绝（内存里 hold 不住）
    max-columns: 200
    batch-size: 1000         # executeBatch 粒度
    query-timeout-sec: 120
    session-ttl: 30m         # session TTL，到期由定时清理
    preview-rows: 20         # 预检返回的样例行数
    max-error-rows: 200      # 错误明细最多回传/落库条数
```

> `spring.servlet.multipart.max-file-size` 已是 100MB（发票上传用），**不改**它 —— 导入在自己的 service 层做 20MB 闸门，避免影响发票上传。

### 3.8 写入实现

```java
// ImportExecutorService.commit() 关键骨架
try (Connection conn = hikari.getConnection()) {
    conn.setAutoCommit(false);                       // 原子
    try (PreparedStatement ps = conn.prepareStatement(builtInsertSql)) {
        ps.setQueryTimeout(cfg.queryTimeoutSec());
        int n = 0;
        for (row : session.rows()) {
            bind(ps, convert(row, mapping, columns));  // 值全绑定
            ps.addBatch();
            if (++n % batchSize == 0) ps.executeBatch();
        }
        ps.executeBatch();
        conn.commit();
    } catch (SQLException ex) {
        conn.rollback();
        throw new BizException(400, "导入失败已回滚: " + ex.getMessage());
    }
}
```

`builtInsertSql` 的列名段由 `ImportTargetResolver` 从元数据查出的**真实列名**拼成（已过白名单 + 方言引号），值段恒为 `?,?,?`。

---

## 4. 数据库迁移

**只写一份**：`backend/src/main/resources/db/migration-prod/V61__add_data_import.sql`（`db/migration/` 只剩 3 个历史文件，新迁移一律进 `migration-prod`，见 AGENTS §4.1 + `FlywayConfig`）。

```sql
-- 1) 权限
INSERT INTO sys_permission (code, name, type) VALUES
  ('data:import:view',       'Data import view',        'API'),
  ('data:import:execute',     'Data import execute',      'API'),
  ('data:import:log:delete',  'Data import log delete',   'API');

-- 2) 角色：admin 全给；operator view+execute；viewer view
--    （按 code 查 role_id，不用 V10 那种硬编码 1/2/3）

-- 3) 菜单：挂在 /data-mgmt (id=40) 下，sort=3
INSERT INTO sys_menu (parent_id, name, path, component, icon, sort, permission, type, status)
SELECT d.id, 'Data Import', '/data-mgmt/import', 'data-mgmt/import/Index', 'UploadFilled', 3,
       'data:import:view', 'MENU', 1
  FROM sys_menu d
 WHERE d.path = '/data-mgmt' AND d.type = 'DIR'
   AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.path = '/data-mgmt/import');

-- 4) 日志表
CREATE TABLE sys_import_log (
    id              BIGSERIAL PRIMARY KEY,
    datasource_id   BIGINT       NOT NULL,
    datasource_name VARCHAR(64)  NOT NULL,   -- 快照：数据源改名/删了日志仍可读
    db_type         VARCHAR(16)  NOT NULL,
    target_schema   VARCHAR(128),
    target_table    VARCHAR(128) NOT NULL,
    file_name       VARCHAR(255) NOT NULL,
    file_size       BIGINT,
    sheet_name      VARCHAR(128),
    import_mode     VARCHAR(16)  NOT NULL DEFAULT 'append',
    total_rows      INTEGER NOT NULL DEFAULT 0,
    inserted_rows   INTEGER NOT NULL DEFAULT 0,
    failed_rows     INTEGER NOT NULL DEFAULT 0,
    skipped_rows    INTEGER NOT NULL DEFAULT 0,
    status          SMALLINT NOT NULL DEFAULT 0,  -- 0=成功 1=部分成功 2=失败
    error_message   VARCHAR(1000),
    error_detail    TEXT,                        -- 前 200 条 ImportErrorRow（JSON）
    duration_ms     BIGINT,
    operator_id     BIGINT,
    operator_name   VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sys_import_log_created ON sys_import_log (created_at DESC);
CREATE INDEX idx_sys_import_log_ds ON sys_import_log (datasource_id, created_at DESC);
```

幂等：权限/角色用 `NOT EXISTS` 守卫；菜单用 `path + type` 判存在（照 V53 的写法，不写死 id）；`CREATE TABLE` 用 `IF NOT EXISTS`。

---

## 5. 前端设计

### 5.1 新增文件

```
src/api/dataImport.ts                              ImportSession / ImportPreview / ImportValidate / ImportResult / ImportLogItem
src/views/data-mgmt/import/Index.vue               页面壳：el-tabs（数据导入 / 导入历史）
src/views/data-mgmt/import/components/StepSource.vue    01 选数据源 + 目标表 + 模板下载
src/views/data-mgmt/import/components/StepFile.vue      02 上传 + sheet/编码/表头选项
src/views/data-mgmt/import/components/StepMapping.vue   03 字段映射 + 选项
src/views/data-mgmt/import/components/StepReview.vue    04 预检结果 + 执行 + 结果
src/views/data-mgmt/import/components/ImportHistory.vue 导入历史 el-table
```

### 5.2 改动

| 文件 | 改动 |
|---|---|
| `src/router/index.ts` | `builtInViews` 加 `'data-mgmt/import/Index': () => import('@/views/data-mgmt/import/Index.vue')` |
| `src/i18n/locales/zh-CN.ts` | `menu.dataMgmt.import.Index` + `menu.data-mgmt.import.Index`（kebab 别名）+ 页面命名空间 `dataMgmtImport.*` |
| `src/i18n/locales/en-US.ts` | 同上 |

> i18n 两套 key 都要给：`menuName()` 先试 `menu.<component>`（→ `menu.data-mgmt.import.Index`），再退到 path 派生的 `menu.data-mgmt.import._`。现有 `dataMgmt` / `data-mgmt` 双份就是这个原因（AGENTS §2）。

### 5.3 页面结构

按 AGENTS §7.1（表单页例外，允许 `el-card` + `el-form`）：

```
page-container fade-in-up imp-page
├─ page-header  数据导入 / 从 Excel、CSV 批量写入外部数据源表   [+ 下载模板]
├─ el-steps（01 选目标表 → 02 上传文件 → 03 字段映射 → 04 预检与执行）
│   ← 数字序在这里合法：这是真正的有序流程
├─ Step1: el-select 数据源 → el-cascader 表 → el-table 目标列（含类型/可空/长度）
├─ Step2: el-upload-dragger（限 .xls/.xlsx/.csv）
│         + sheet 选择 / CSV 编码 / 是否含表头行 / 行数上限提示
├─ Step3: el-table 映射（源表头 | → | 目标列下拉），同名列自动配对并高亮
│         + 选项：跳过空行 / 空串转 null / 超长截断 / 遇错继续
│         + 右侧固定「样例数据预览」(前 20 行)
├─ Step4: 预检结果卡（总行数 / 将插入 / 类型错误 N / 超长 N / 目标表当前行数）
│         → [开始导入] → 结果（成功/失败/跳过 + 耗时 + 错误明细表 + 导出错误 CSV）
└─ el-tab-pane「导入历史」：ImportHistory.vue（§8 操作列规范：图标直显，删除进 ···）
```

视觉：沿用 §7.2 双层吸顶、§7.3 token（不硬编码 `white`）、§8.3 `tabular` / `mono-time`。

### 5.4 字段映射自动匹配

```ts
// 归一化后同名即自动配对，减少手工操作
const norm = (s: string) => s.replace(/[\s_\-()（）]/g, '').toLowerCase();
// 「用户 姓名」≡「user_name」≡「用户姓名」→ 自动映射
```

---

## 6. 实施阶段

### Phase 1 —— 追加导入闭环（建议先做这个）

- [ ] `V61__add_data_import.sql`（perm + role_perm + menu + log 表）
- [ ] `DatasourceSchemaDto` 加长度/精度；`DatasourceSchemaService` 三处 SQL 补列
- [ ] `DatasourceService.loadDecrypted(id, permCode)` 重载
- [ ] `ImportFileReader`（xlsx/xls/csv，POI 流式，UTF-8/GBK 探测）
- [ ] `ImportTypeConverter` + `ImportTargetResolver` + `ImportSafetyChecker`
- [ ] `ImportSessionService` + `ImportExecutorService`（validate / commit）
- [ ] `SysImportLog` + `Repository` + `ImportLogService`
- [ ] `ImportController`（7 个接口）+ `application.yml` 限额
- [ ] 前端：`dataImport.ts`、5 个组件、`Index.vue`、router、i18n（zh/en）
- [ ] 模板下载接口 + 前端按钮

**验收**：拿一个真实外部 MySQL 表，导入 3 类文件 —— ① 表头完全匹配 ② 表头部分匹配需手工映射 ③ 含 5 条脏数据（错日期/超长/空必填）。要求：脏数据行在预检里被列出行号+原因，执行后成功行入库、失败行可导出。viewer 看不到「开始导入」按钮且调 commit 返回 403。

### Phase 2 —— 危险模式与体验

- [ ] `truncate` 模式（`data:datasource:update` + 输表名二次确认）
- [ ] 错误明细完整 CSV 下载（当前只存前 200 条）
- [ ] 导入会话跨页签保留（前端 Pinia 存 sessionId，切 tab 回来不用重传）
- [ ] 大文件降级：session 从「内存存行」改为「临时文件存行」（接口不变，只换 `ImportSessionService` 内部）

### Phase 3 —— 进阶

- [ ] `upsert` 模式（需主键/唯一键元数据）
- [ ] 目标表不存在时「按模板建表」（需要一套受控 DDL 白名单，风险高，慎做）
- [ ] 字段转换表达式（沙箱求值）
- [ ] 导入模板 / 映射方案保存复用（「我的导入模板」）

---

## 7. 风险清单

| 风险 | 应对 |
|---|---|
| 误导入生产库（导错表/导两次） | 预检强制展示目标表全名 + 当前行数；执行前 `ElMessageBox` 要求确认；日志留痕可追溯 |
| 标识符注入 | §3.4 四层防线，执行期重查元数据 |
| 大文件打爆内存 | `max-rows` / `max-file-size` 硬闸门；POI 流式读；Phase 2 改临时文件 |
| 长事务锁表 | 单事务 + 30s 超时；Phase 2 truncate 模式提示用户在低峰期执行 |
| 外部库连接被导入打满 | 复用 `DatasourceConnectionService`（池 max=4），并限制并发导入数（Phase 2 加信号量） |
| 中文 CSV 乱码 | 编码下拉（UTF-8 / GBK / GB18030）+ 后端 BOM 与全角标点兜底 |
| 列长度未知导致超长 | §3.2 补 `maxLength`；超长默认截断 + warning，可关 |

---

## 8. 待确认（我给了默认值，确认后即可开工）

| # | 问题 | 我的默认建议 |
|---|---|---|
| 1 | 是否允许导入本项目主库 PG？ | **不允许**（v1 只做外部数据源） |
| 2 | 是否需要「先清空表再导入」？ | Phase 2 做，且必须输表名二次确认 |
| 3 | 单文件上限？ | 20MB / 5 万行 |
| 4 | 脏数据策略默认？ | 默认「跳过错误行继续」+ 全部落日志；也可选「遇错即停」 |
| 5 | 导入历史保留多久 / 要不要清理入口？ | 永久保留（审计），先不做自动清理 |
| 6 | 是否需要「导入模板保存复用」？ | Phase 3，先不做 |
| 7 | 顺便修 `schema` 接口的 403（viewer）？ | 建议修，但**单独一条迁移**，不混进 V61 |