-- V55: 报表模块 sys_report + 权限码 + 角色绑定 + /bi 子菜单
--
-- 定位：报表 = 「查询头 + 参数化 SQL + 虚拟滚动表格」的组合视图。
-- 报表本身**不含 SQL**，它只引用 sys_query.id（与 BI 大屏的数据绑定同一思路）：
-- SQL 与参数 schema 的唯一真源仍在 sys_query，改 SQL 不用改报表。
--
-- 为什么查询头不另存一份：
--   sys_query.params_schema_json 已经是「SQL 里 :name 对应什么 UI 控件」的现成契约
--   （QueryParamSchema: name/label/type/required/defaultValue/placeholder）。
--   报表层再存一份 schema 就会有两个真源，改查询参数时报表不同步 → 静默错参。
--   所以报表只存「查询头显示哪些、怎么排」，字段定义一律运行时从 query 读。
--
-- 字段说明：
--   code             业务编码，全局唯一
--   query_id         指向 sys_query.id 的数据源查询（ON DELETE RESTRICT：删查询前得先处理报表）
--   header_json      查询头布局 HeaderLayout（visible 字段顺序 + 折叠 + 每行字段数）
--   columns_json     列配置 ColumnCfg[]（width/align/fixed/label/visible），首次由查询结果列自动生成
--   page_size        虚拟滚动每页行数（默认 50，上限 2000）。50 ≈ 一屏多一点：
--                    首屏不必等太久，滚到底再拉也够顺；调到几百只让首屏请求变慢，
--                    对虚拟滚动没有额外收益（DOM 始终只渲染一屏）
--   max_rows         该报表允许加载的总行数护栏（默认 50000，0=不限制）。真分页的意义就在于
--                    内存有上界，但客户端仍可能一路滚到底，所以留一道总量闸门
--   default_params   查询头默认值 JSON（name → value），打开报表即可出数
--   status           0=草稿 / 1=已发布
--
-- 时间列用 TIMESTAMP（与 V52 / sys_form_design 全库一致），不用 TIMESTAMPTZ。
--
-- 幂等：权限码 / 角色绑定 / 菜单均用 NOT EXISTS 守卫；菜单不写死 id（BIGSERIAL 自增）。

-- ---------------------------------------------------------------------------
-- 1. 建表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_report (
  id             BIGSERIAL     PRIMARY KEY,
  code           VARCHAR(64)  NOT NULL UNIQUE,
  name           VARCHAR(128) NOT NULL,
  description    VARCHAR(512),
  query_id       BIGINT       NOT NULL REFERENCES sys_query(id) ON DELETE RESTRICT,
  header_json    TEXT,
  columns_json   TEXT,
  default_params TEXT,
  page_size      INTEGER      NOT NULL DEFAULT 50,
  max_rows       INTEGER      NOT NULL DEFAULT 50000,
  status         SMALLINT     NOT NULL DEFAULT 0,
  created_by     VARCHAR(64)  NOT NULL,
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_report_page_size CHECK (page_size >= 20 AND page_size <= 2000),
  CONSTRAINT ck_report_max_rows  CHECK (max_rows >= 0)
);

-- 列表页主查询：按更新时间倒序；按 query_id 反查「哪些报表用了这个查询」也要走索引
CREATE INDEX IF NOT EXISTS idx_report_status ON sys_report (status, updated_at);
CREATE INDEX IF NOT EXISTS idx_report_query  ON sys_report (query_id);

-- ---------------------------------------------------------------------------
-- 2. 权限码（AGENTS §3 格式 <module>:<resource>:<verb>）
-- ---------------------------------------------------------------------------
--   report:view        查看报表 / 打开报表页（含执行取数）
--   report:create      新建 / 复制
--   report:update      编辑（含 header / columns / page_size）
--   report:delete      删除
--   report:publish     发布 / 取消发布
--   report:run         执行报表取数（与 view 分开：viewer 可以看报表但不允许任意传参探测数据源）

INSERT INTO sys_permission (code, name, type)
SELECT 'report:view', 'Report view', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'report:view');

INSERT INTO sys_permission (code, name, type)
SELECT 'report:create', 'Report create', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'report:create');

INSERT INTO sys_permission (code, name, type)
SELECT 'report:update', 'Report update', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'report:update');

INSERT INTO sys_permission (code, name, type)
SELECT 'report:delete', 'Report delete', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'report:delete');

INSERT INTO sys_permission (code, name, type)
SELECT 'report:publish', 'Report publish', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'report:publish');

INSERT INTO sys_permission (code, name, type)
SELECT 'report:run', 'Report run', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'report:run');

-- ---------------------------------------------------------------------------
-- 3. 角色绑定
-- ---------------------------------------------------------------------------
-- admin：全部 6 个
-- operator：view/create/update/publish/run（不给 delete）
-- viewer：view/run（只消费报表，不碰管理）

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'admin'
   AND p.code IN ('report:view', 'report:create', 'report:update',
                  'report:delete', 'report:publish', 'report:run')
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'operator'
   AND p.code IN ('report:view', 'report:create', 'report:update',
                  'report:publish', 'report:run')
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'viewer'
   AND p.code IN ('report:view', 'report:run')
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

-- ---------------------------------------------------------------------------
-- 4. 菜单：/bi 下新增「报表管理」子菜单
-- ---------------------------------------------------------------------------
-- sort=3（现有 /bi 下 Dashboard Manager=1 / Video Wall=2）。
-- 与 BI 大屏一致：只给**无路径参数**的列表页建菜单行。
-- 设计器 / 报表页都是「操作入口」，从列表页按钮进入；对应路由放在 staticRoutes，
-- sys_menu 不写 '/bi/report/design/:id' 这种占位符 path（V54 已记录该坑）。
INSERT INTO sys_menu (parent_id, name, path, component, icon, sort, permission, type, status)
SELECT d.id, 'Report Manager', '/bi/report', 'bi/report/Index', 'Tickets', 3,
       'report:view', 'MENU', 1
  FROM sys_menu d
 WHERE d.path = '/bi' AND d.type = 'DIR'
   AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.path = '/bi/report');
