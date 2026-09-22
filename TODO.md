# 待办事项 / Backlog

记录功能迭代中发现的、需要后续规划处理的问题。完成一项时整体移除或移入 CHANGELOG。

---

## [P1] ✅ 已完成（方案 C，2026-09-22）：全库操作的「5000 条静默截断」问题

**结论**：已按方案 C 重构，彻底去掉 5000 上限，三处操作全部改用 SQL `GROUP BY` + 真·服务器端分页。

**改动摘要**：

| 位置 | 旧实现 | 新实现 |
|---|---|---|
| `InvoiceRecordRepository` | 无分组 SQL | 新增 `findDuplicateInvoiceNumbers` / `findByInvoiceNumberOrderByIdAsc` / `listGroupKeys` / `countGroupKeys` / `findAllByIdIn`；`findAllForDuplicateCheck` 标记 `@Deprecated` |
| `InvoiceService.findDuplicates` | 拉 5000 行内存分组 | SQL 查重复号 → 逐号查明细按 ext 细分 |
| `InvoiceService.listGrouped` | 拉 5000 行 + 内存分组 + 手工切片分页 | 两步 SQL:`listGroupKeys` GROUP BY + LIMIT/OFFSET 真分页 → 按 rep_id 批量查明细 |
| `InvoiceService.dedupe` | 拉 5000 行内存分组 + 逐组删 | SQL 先查重复号列表 → 逐号查明细删，单组内存开销极小 |
| `InvoiceController` | 硬编码 `5000` | 改为 `0`（参数保留仅为 service 签名兼容，内部已忽略） |

**关键 SQL**（只用 `COALESCE` / `NULLIF` / `CAST(x AS TEXT)` / `||`，SQLite 与 PG 双方言兼容）：

```sql
-- 分组键把 orphan 记录（invoice_number 为空）各自成组
SELECT COALESCE(NULLIF(invoice_number,''), '__orphan__#' || CAST(id AS TEXT)) AS gkey,
       MAX(id) AS rep_id,
       COUNT(*) AS cnt
FROM invoice_record
WHERE <筛选条件>
GROUP BY gkey
ORDER BY rep_id DESC
LIMIT :size OFFSET :offset;
```

**内存压力**：从 O（全库行数） 降为 O（当前页大小 × 平均组内记录数）。

**已验证**:
- `mvn compile` BUILD SUCCESS
- dev SQLite 实际数据（8 行 / 6 组 / 2 个重复号）跑 `findDuplicateInvoiceNumbers` / `listGroupKeys` / `countGroupKeys` 结果正确

<details>
<summary>原问题描述（点击展开存档）</summary>

**现状**：以下三处全库操作在数据量超过 5000 条时静默截断：

- `InvoiceService.findDuplicates(int limit)`
- `InvoiceService.listGrouped(req, int limit)`
- `InvoiceService.dedupe()`
- 控制器 `InvoiceController` 传入硬编码 `5000`

**问题**:Java 端分组一次性拉全量进 JVM 内存，5000 上限防 OOM；超量数据被悄悄丢弃，前端拿到不完整列表。

**改进方案**:A（配置项）/ B（超量抛异常）/ C(SQL GROUP BY)。**已采用 C**。

</details>

## [P0] 评估：后端数据库是否支持 PostgreSQL（默认仍是 SQLite）

**完成日**：2026-09-21（字典扩展实施后）

**结论**：

| 维度 | 状态 |
|---|---|
| **默认 DB** | **SQLite**（dev profile，`application-dev.yml` 指向 `./data/fapiao-web.db`） |
| **PostgreSQL 支持** | **已就绪**（prod profile 配置完整，含 driver、dialect、Flyway PG adapter） |
| **两套 SQL 迁移** | 已存在（`migration-dev/V1..V6` vs `migration-prod/V1..V6`），主键语法已方言化 |
| **运行时切换** | 通过 `SPRING_PROFILES_ACTIVE=prod` + 设置 `PG_HOST/PG_PORT/PG_USER/PG_PASSWORD/PG_DB` 即可 |

**配置位置**：

- `application-dev.yml`：SQLite（`jdbc:sqlite:./data/fapiao-web.db`）
- `application-prod.yml`：PostgreSQL（`jdbc:postgresql://${PG_HOST}/${PG_DB}`）
- `application.yml`：默认 `profiles.active: dev`

**PostgreSQL 兼容性清单（已就绪）**：

| 项 | 状态 | 备注 |
|---|---|---|
| JDBC Driver `org.postgresql.Driver` | ✓ | pom 已有 |
| Flyway PG dialect | ✓ | `flyway-database-postgresql` 已声明 |
| Hibernate dialect | ✓ | `PostgreSQLDialect` |
| 主键自增 | ✓ | dev 用 `INTEGER AUTOINCREMENT`，prod 用 `BIGSERIAL PRIMARY KEY` |
| 外键 CASCADE | ✓ | 两边 SQL 都声明 |
| DATE / TIMESTAMP | ✓ | 两边都支持 |
| DECIMAL vs NUMERIC | ✓ | prod 用 `NUMERIC(18,2)` |
| `connection-init-sql: 'PRAGMA foreign_keys = ON'` | ⚠ | **dev only** — prod 用 PostgreSQL 默认启用 FK，无需此配置 |
| LocalDate → DATE 列存储 | ✓ | Hibernate `Asia/Shanghai` JDBC timezone 已设 |
| LocalDateTime → TIMESTAMP 存储 | ✓ | Hibernate `Asia/Shanghai` JDBC timezone 已设 |

**SQL 迁移两套拆分（关键差异）**：

- `dev/V1__init.sql`：`INTEGER PRIMARY KEY AUTOINCREMENT`，`DECIMAL(18,2)`
- `prod/V1__init.sql`：`BIGSERIAL PRIMARY KEY`，`NUMERIC(18,2)`
- `V2__seed.sql`（共享）：admin 用户/角色/权限/菜单/系统设置 — 两边用同一份（语法兼容）
- `V3..V6`（dev/prod 各一份）：菜单调整、OCR 菜单改名、添加核销字段、添加字典表

**未做（潜在改进点）**：

| # | 项 | 备注 |
|---|---|---|
| 1 | V6 prod 版本未补 `admin` 角色权限 (`SELECT 1, id FROM sys_permission WHERE code LIKE 'dict:%'`) | dev/prod 两边 SQL 一样，prod 是新建库无所谓，但若要在已有 prod 库升级需手工补 |
| 2 | Flyway `flyway_schema_history` 表的 baseline | 当前 dev/prod 各跑各的，无 baseline on migrate，未做迁移验证 |
| 3 | JPA Entity 注解 `@GeneratedValue(strategy = IDENTITY)` | SQLite + PostgreSQL 都支持，无需调整 |
| 4 | Hikari pool 配置 | dev `maximum-pool-size: 1`（SQLite 锁限制），prod `maximum-pool-size: 20`，已按方言区分 |
| 5 | S3 存储 / MinIO | prod 环境通常配合 PG 部署，已有 AWS SDK v2 依赖 |

**建议**：

1. **默认保持 SQLite**（开发简单、零运维、单文件备份方便）
2. **生产部署推荐 PostgreSQL**（多用户并发、事务隔离级别、外键级联、备份恢复工具更成熟）
3. 切换时只需：
   ```bash
   export SPRING_PROFILES_ACTIVE=prod
   export PG_HOST=db.example.com PG_PORT=5432 PG_DB=fapiao_web PG_USER=fapiao PG_PASSWORD=xxx
   ```
4. 首次部署到空 prod 库会自动跑 V1~V6 全部迁移，无需手工补数据

**触发条件**：当 SQLite 出现「database is locked」频繁 / 并发写入失败 / 单文件 > 1GB 时，迁移到 PostgreSQL。

---

## [P3] 字典变更审计日志

**现状**：字典 CRUD（type/item 的新增/更新/删除）目前无审计记录。

**建议**：
- 方案 A（轻量）：在 `sys_dict_*` 表加 `updated_by` / `updated_at` 字段，记录最后操作人和时间
- 方案 B（完整）：新建 `sys_dict_audit` 表，type 和 item 的每次 CUD 都记录一条
- 方案 C（最低成本）：直接用 Hibernate Envers 拦截 `@Entity`，自动生成审计表

**触发条件**：监管/合规要求、或需要回溯「谁在什么时候把票种改了」时。

---

## [P3] `invoice_type`（票种）接入字典

**现状**：DB 字段 `invoice_record.invoice_type VARCHAR(128)` 是 free-form 字符串，由 OCR API 直接写入，没有受控枚举。

**建议**：
1. 新增字典 `invoice_type`，种入 `电子发票（普通发票）` / `增值税专用发票` 等常见票种
2. 上传页 List.vue 列表列加筛选项 `el-select`，数据源用 `useDict('invoice_type')`
3. List.vue 列展示用字典 `label` 替代 raw 字符串

**触发条件**：需要按票种筛选导出 / 统计时。

---

新增待办请按以下模板追加：

```
## [优先级] 标题

**现状**：...
**问题**：...
**改进方案**：...
**建议**：...
**触发条件**：...
```
---

## 维护说明

新增待办请按以下模板追加：

```
## [优先级] 标题

**现状**：...
**问题**：...
**改进方案**：...
**建议**：...
**触发条件**：...
```
