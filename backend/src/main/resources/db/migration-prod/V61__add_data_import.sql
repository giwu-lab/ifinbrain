-- V61: 数据导入模块（Excel / CSV → 外部数据源表）
--
-- 背景
--   数据管理模块（/data-mgmt）已有「数据源」（sys_datasource）与「自定义查询」
--   （sys_query + QueryExecutor）。但查询链路是 SqlSafetyChecker 强制 SELECT-only 的
--   只读通道，**没有任何写外部库的入口** —— 业务方要给 ERP 的业务表补历史数据，
--   只能走 DBA 手写 SQL 或直接在库外操作，两者都留不下「谁导的」痕迹。
--
-- 本次新增
--   页面：/data-mgmt/import（数据导入），4 步向导：选目标表 → 上传 → 字段映射 → 预检并执行
--   写入：ImportExecutorService 单事务批量 INSERT（值全部 PreparedStatement 绑定）
--
-- 权限码（AGENTS §3 格式 <module>:<resource>:<verb>）：
--   data:import:view       选数据源/表、上传解析、字段映射预检、下载模板、看导入历史
--   data:import:execute     **真正写库**（commit）
--   data:import:log:delete  删除导入日志（审计痕迹，默认不给 operator）
--
-- 为什么不复用 data:datasource:*：
--   「配置一个数据源连接」与「往这张业务表里灌 3 万行数据」是两件事，权限必须正交。
--   否则给 operator 开「改数据源」就等于开「写生产库」。本模块完全独立成一套权限位。
--
-- 角色分配：
--   admin    全部 3 个
--   operator view + execute（数据录入是运营日常；不给 log:delete —— 日志是审计痕迹）
--   viewer   仅 view（可浏览历史 / 看预检，不给 commit）
--
-- 菜单：挂在顶级 DIR /data-mgmt 下，sort=3（现有 Data Sources=1、Saved Queries=2）。
--       component 必须与 router/index.ts 的 builtInViews key 完全一致。
--       icon 用 Element Plus 图标名（V59 已把旧的自造名统一成 EP 名）。
--
-- 幂等：权限 / 角色绑定 / 菜单全部 NOT EXISTS 守卫；建表 IF NOT EXISTS；重复执行无副作用。

-- ---------------------------------------------------------------------------
-- 1. 权限码
-- ---------------------------------------------------------------------------
INSERT INTO sys_permission (code, name, type)
SELECT 'data:import:view', 'Data import view', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'data:import:view');

INSERT INTO sys_permission (code, name, type)
SELECT 'data:import:execute', 'Data import execute (writes to target table)', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'data:import:execute');

INSERT INTO sys_permission (code, name, type)
SELECT 'data:import:log:delete', 'Data import log delete', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'data:import:log:delete');

-- ---------------------------------------------------------------------------
-- 2. 角色绑定（按 code 查 role_id，不写死 1/2/3 —— V10 那种硬编码是历史包袱）
-- ---------------------------------------------------------------------------
-- admin：全部
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'admin'
   AND p.code IN ('data:import:view', 'data:import:execute', 'data:import:log:delete')
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

-- operator：可导入，不可删日志
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'operator'
   AND p.code IN ('data:import:view', 'data:import:execute')
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

-- viewer：只读（看历史 / 看预检结果）
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'viewer'
   AND p.code = 'data:import:view'
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

-- ---------------------------------------------------------------------------
-- 3. 子菜单：数据导入
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (parent_id, name, path, component, icon, sort, permission, type, status)
SELECT d.id, 'Data Import', '/data-mgmt/import', 'data-mgmt/import/Index', 'UploadFilled', 3,
       'data:import:view', 'MENU', 1
  FROM sys_menu d
 WHERE d.path = '/data-mgmt' AND d.type = 'DIR'
   AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.path = '/data-mgmt/import');

-- ---------------------------------------------------------------------------
-- 4. 导入日志（审计）
-- ---------------------------------------------------------------------------
-- 为什么必须有：导入是本项目唯一「往外部生产库写数据」的能力。
--   事后追责/排查「这张表怎么多了 3 万行、谁灌的」全靠这张表。
--
-- datasource_name / operator_name 是**快照**而非外键：数据源或用户被改名/删除后，
--   历史记录仍要能读出「当时是谁导到哪张表」；用外键会连带把历史一起抹掉。
--
-- error_detail 只存前 200 条（application.yml 的 ifinbrain.import.max-error-rows），
--   完整明细导出放 Phase 2（需落文件，避免撑大 TEXT 列）。
CREATE TABLE IF NOT EXISTS sys_import_log (
    id              BIGSERIAL    PRIMARY KEY,
    datasource_id   BIGINT       NOT NULL,
    datasource_name VARCHAR(64)  NOT NULL,
    db_type         VARCHAR(16)  NOT NULL,
    target_schema   VARCHAR(128),
    target_table    VARCHAR(128) NOT NULL,
    file_name       VARCHAR(255) NOT NULL,
    file_size       BIGINT,
    sheet_name      VARCHAR(128),
    import_mode     VARCHAR(16)  NOT NULL DEFAULT 'append',
    total_rows      INTEGER      NOT NULL DEFAULT 0,
    inserted_rows   INTEGER      NOT NULL DEFAULT 0,
    failed_rows     INTEGER      NOT NULL DEFAULT 0,
    skipped_rows    INTEGER      NOT NULL DEFAULT 0,
    -- 0=成功 1=部分成功（有跳过行） 2=失败（整体回滚）
    status          SMALLINT     NOT NULL DEFAULT 0,
    error_message   VARCHAR(1000),
    error_detail    TEXT,
    duration_ms     BIGINT,
    operator_id     BIGINT,
    operator_name   VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_sys_import_log_created ON sys_import_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sys_import_log_ds      ON sys_import_log (datasource_id, created_at DESC);