# iFinBrain

发票批量识别与管理系统。基于 **Spring Boot 4.1 + Vue 3**，调用 [`fapiao_cn`](../fapiao_cn) 服务做 OCR 识别，文件存储可热切换「本地磁盘 / S3 兼容存储」。

## 特性

- 🔐 **RBAC 权限**：用户-角色-权限-菜单四级，后端 `@PreAuthorize` + 前端菜单按权限裁剪
- 📁 **可切换存储**：默认本地磁盘，可在「系统管理 → 存储设置」页面切到 S3（MinIO / 腾讯 COS / 阿里 OSS / 华为 OBS 等）
- 📤 **批量识别**：上传 PDF/XML，逐个或批量调用 `fapiao_cn /extract[/batch]`，结果落库、可编辑、可下载
- 🎨 **现代前端**：Vue 3 + Vite + TypeScript + Element Plus + Pinia
- 🔑 **JWT 鉴权**：Access + Refresh 双 token，自动续签

## 目录结构

```
ifinbrain/
├── backend/           # Spring Boot 4.1.1 + JDK 21
├── frontend/          # Vue 3 + Vite + Element Plus
├── .env.example
├── docker-compose.yml # 仅 prod 部署使用；dev 用源码
└── README.md
```

## 两种启动模式

| 模式 | 后端 | 前端 | 数据库 | 何时用 |
|---|---|---|---|---|
| **dev** | `mvn spring-boot:run` | `npm run dev` | PostgreSQL（`dev` profile） | 日常开发，热重载 |
| **prod** | docker compose | docker compose | PostgreSQL（`prod` profile） | 服务器部署 |

> **唯一数据库是 PostgreSQL**，dev 与 prod 连同一个库。SQLite 已弃用：`sqlite-jdbc` 依赖与 `db/migration-dev/` 迁移目录均已移除。
> 两种模式只是连接池大小和批量参数不同，不切数据库。需要 pgvector 扩展（`pgvector/pgvector:pg18` 镜像）。

---

## dev 模式（源码启动）

dev 是日常开发模式，三个进程同时跑在本地，热重载可用。

### 0. 前置
- JDK 21 + Maven 3.9+
- Node.js 18+（推荐 22）
- 已运行的 `fapiao_cn` 服务（端口 8091）

### 1. 启动 `fapiao_cn`（如未运行）

```bash
# 方式 A：docker
cd /home/coder/workspace/fapiao_cn && docker compose up -d

# 方式 B：本地 venv（推荐 dev，能看日志）
cd /home/coder/workspace/fapiao_cn
.venv/bin/python -m uvicorn fapiao_cn.server:app --host 0.0.0.0 --port 8091
```

确认活着：`curl http://localhost:8091/health` → `{"status":"ok"}`

### 2. 启动后端（终端 A）

```bash
cd /home/coder/workspace/ifinbrain/backend
mvn -DskipTests spring-boot:run
```

默认 profile = `dev`，连 PostgreSQL；本地存储落在 `./data/storage/`。

> 必填的环境变量：`PG_HOST` / `PG_PORT` / `PG_DB` / `PG_USER` / `PG_PASSWORD`（连远程库）、
> `JWT_ACCESS_SECRET`（≥32 字节）、`IFINBRAIN_MASTER_KEY`（≥32 字节）。
> 识别 API（fapiao_cn 的 base-url / api-key）**不在 .env 中配置**，请在「系统 → API 设置」页面填写。

后端起来：`curl http://localhost:8080/actuator/health` → `{"status":"UP"}`

### 3. 启动前端（终端 B）

```bash
cd /home/coder/workspace/ifinbrain/frontend
npm install   # 第一次
npm run dev
```

Vite dev server 监听 `http://localhost:5173`，自动把 `/api/*` 反代到 `http://localhost:8080`。

### 4. 登录

浏览器打开 <http://localhost:5173>

默认账号 `admin / Admin@123`（首次登录请改密码）。

---

## 部署（docker-compose）

`ifinbrain` 是**纯独立**项目：所有依赖（数据库、OCR 服务）都视为外部服务通过环境变量注入，不假设任何 docker 网络共享。

### 默认模式：PostgreSQL + 本地磁盘存储

#### 前置
- 已运行的 PostgreSQL 实例，**装了 pgvector 扩展**（`CREATE EXTENSION vector;`）
- 已运行的 `fapiao_cn` 服务（任意位置），知道其 URL 与 `extract` scope 的 API Key

#### 启动
```bash
cp .env.example .env
# 编辑 .env，填入：
#   PG_HOST=<PG 地址>  PG_PORT=5432  PG_DB=ifinbrain
#   PG_USER=<用户>    PG_PASSWORD=<密码>
#   JWT_ACCESS_SECRET=<≥32 字节随机串>            # openssl rand -base64 32
#   IFINBRAIN_MASTER_KEY=<≥32 字节随机串>        # openssl rand -base64 48
docker compose up -d
```

> ⚠️ **2026-09-22 安全轮换**：历史 `.env` 曾误提交真实密钥到工作目录（且日志 `backend/logs/console.log` 前 3 行曾包含明文 `FC_API_KEY`)。已作如下处理：
> - `.env` 中的 `JWT_ACCESS_SECRET` / `IFINBRAIN_MASTER_KEY` 已轮换为新随机值（旧值作废，请同步更新所有部署环境）
> - 新增 `.gitignore`，把 `.env` / `logs/` / `*.log` 等敏感路径排除
> - **fapiao_cn 侧的 API Key 需要用户自行轮换**（因为它在 fapiao_cn 项目内，不在本仓库）
> - 由于 master key 轮换，**旧的 sys_setting 加密字段将无法解密**——首次启动后到「系统 → API 设置 / 存储设置」重新填一次密钥即可

默认 profile = `prod`（compose 里已固定），后端容器内：
- 数据库：外部 PostgreSQL（由 `PG_*` 环境变量注入，compose 不内置 PG 服务）
- 存储：`/app/data/storage`（named volume `backend_data` 内的子目录）

访问：
- 前端：<http://localhost:8088>
- 后端：<http://localhost:8080>

> 不再启动 `fapiao_cn`、不再启动 bundled postgres。两者都是外部服务。

### 切换到 PostgreSQL（可选）

在 `.env` 中取消 `SPRING_PROFILES_ACTIVE` 与 `PG_*` 行的注释并填值：

```bash
SPRING_PROFILES_ACTIVE=prod
PG_HOST=10.0.0.5            # 远程 PG 地址
PG_PORT=5432
PG_DB=ifinbrain
PG_USER=fapiao
PG_PASSWORD=your-secret
# PG_QUERY_STRING=?sslmode=require   # 可选
```

重启后端容器即可。后端日志会显示 `Flyway: applied 2 migrations [profile=[prod]]`，迁移在远程 PG 上自动执行。

> 远程 PG 需先手工建库建用户（Flyway 只管表结构，不管库/角色）：
> ```sql
> CREATE DATABASE ifinbrain;
> CREATE USER fapiao WITH PASSWORD 'your-secret';
> GRANT ALL PRIVILEGES ON DATABASE ifinbrain TO fapiao;
> \c ifinbrain
> GRANT ALL ON SCHEMA public TO fapiao;
> ```
>
> 同主机部署时 PG 主机地址：Linux 用 `172.17.0.1`，Mac/Windows 用 `host.docker.internal`。

## 数据库

**唯一数据库是 PostgreSQL**，SQLite 已弃用（驱动依赖与 `db/migration-dev/` 目录已移除）。

| Profile | Driver | 差异 | Migration 目录 |
|---|---|---|---|
| dev | `org.postgresql.Driver` | 连接池 10 / 2，批量关闭 | `db/migration/` + `db/migration-prod/` |
| prod | `org.postgresql.Driver` | 连接池 20 / 5，批量 50、时区 `Asia/Shanghai` | `db/migration/` + `db/migration-prod/` |

两种 profile 连**同一个库**，只有连接池和 Hibernate 批量参数不同。迁移目录由 `FlywayConfig` 固定指定，
不再按方言或 profile 分支。需要 pgvector 扩展（`CREATE EXTENSION vector;`）—— V45 迁移与 RAG 检索都依赖它。

## 关键 API

| Method | Path | 权限 |
|---|---|---|
| POST | `/api/auth/login` | 公开 |
| POST | `/api/auth/refresh` | 公开 |
| GET  | `/api/auth/me` | 登录后 |
| GET  | `/api/system/users` | `system:user:list` |
| GET  | `/api/system/roles` | `system:role:list` |
| GET  | `/api/system/menus/tree` | 登录后 |
| GET  | `/api/system/settings/storage` | `setting:view` |
| PUT  | `/api/system/settings/storage` | `setting:update` |
| GET  | `/api/invoices?page=&size=&keyword=` | `invoice:list` |
| POST | `/api/invoices/upload-and-recognize` | `invoice:upload` |
| GET  | `/api/files/raw?key=...` | `file:download` |

完整 RBAC 权限点见 seed 脚本：`system:*` / `invoice:*` / `setting:*` / `file:*`。

## 默认账号 & 角色

| 用户名 | 密码 | 角色 |
|---|---|---|
| `admin` | `Admin@123` | `admin`（全权） |

可手动在「系统管理 → 用户」页新增 `operator` / `viewer` 角色用户。

## 配置项

`backend/src/main/resources/application.yml` + profile 覆盖：

| Key | 含义 |
|---|---|
| `ifinbrain.jwt.access-secret` | JWT 签名密钥（≥ 32 字节） |
| `ifinbrain.jwt.access-ttl-minutes` | Access token 有效期 |
| `ifinbrain.jwt.refresh-ttl-days` | Refresh token 有效期 |
| `ifinbrain.cors.allowed-origins` | 允许跨域的前端地址 |
| `ifinbrain.storage.type` | `local` / `s3` |
| `ifinbrain.storage.local.root` | 本地存储根目录 |
| `ifinbrain.storage.s3.*` | S3 兼容存储配置（运行时可通过「存储设置」覆盖） |

> **fapiao_cn（识别服务）的 base-url / api-key / timeout 不在 `application.yml` 中**，而是通过「系统 → API 设置」页面写入 `sys_setting` 表的 `ocr.*` key，由 `FapiaoCnClient` 直接读取。

环境变量（部署期可覆盖）：
- `PG_HOST` / `PG_PORT` / `PG_DB` / `PG_USER` / `PG_PASSWORD`：PostgreSQL 连接信息（dev / prod 都要）
- `SPRING_PROFILES_ACTIVE`：切换 dev / prod（只影响连接池与批量参数，不切数据库）
- `DB_POOL_MAX`：prod 连接池上限，默认 20
- `APP_STORAGE_LOCAL_ROOT`：本地存储根目录（容器内默认 `/app/data/storage`）

## 存储设置（运行时修改）

登录 admin 后 → 「系统管理 → 存储」页：

- **本地磁盘**：默认 `${APP_STORAGE_LOCAL_ROOT}`（容器内 `/app/data/storage`，本机 `./data/storage`），上传的文件落在 `${root}/yyyy/MM/{sha256}-{filename}`
- **S3 兼容**：填入 endpoint / bucket / ak / sk，点击「保存」后**立即生效**（不需要重启），新上传将走 S3；老文件保留不动

> 注意：S3 切换不会迁移历史文件。切换前请自行处理。

## 已知限制

- dev / prod 连接同一个 PostgreSQL 实例，请确保它装了 `pgvector` 扩展。
- schema 完全由 Flyway 管理（`ddl-auto: none`），改表结构只能写 `V{n}__*.sql` 迁移，不要手工 DDL。
- 上传分批默认 50 文件 / 10 MB（与 `fapiao_cn` 对齐）。
- 切换到 prod profile 前，需先在外部 PG 上手工创建 database / user / schema 权限（Flyway 只管表）。

## License

Apache-2.0。完整协议见 [`LICENSE`](LICENSE)，版权与第三方归属声明见 [`NOTICE`](NOTICE)。

第三方组件：`frontend/LICENSE-NOTICE` 保留了 `@file-viewer/*`（Flyfish Dev）的 Apache-2.0 §4(d)
归属声明，`frontend/LICENSE-APACHE-2.0` 为其许可文本，二者在任何分发或衍生作品中都必须保留。