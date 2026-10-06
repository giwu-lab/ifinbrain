-- V25: 页面设计器（Phase B MVP）
-- sys_portal_page   —— 门户页（一份 layout_schema + 角色绑定）
-- sys_portal_block  —— 内置组件字典（6 个内置块 + 2 个 layout 容器）
--
-- JSON 列约定：统一 TEXT 存储（SQLite/PG 通用），后端用 Jackson 解析。
-- 与现有 sys_query.params_schema_json 保持一致风格。

-- ---------------------------------------------------------------------------
-- 1. sys_portal_page
-- ---------------------------------------------------------------------------
CREATE TABLE sys_portal_page (
  id              BIGSERIAL       PRIMARY KEY,
  code            VARCHAR(64)   NOT NULL UNIQUE,
  name            VARCHAR(128)  NOT NULL,
  description     VARCHAR(512),
  layout_schema   TEXT          NOT NULL DEFAULT '{"version":1,"root":{"id":"root","type":"page","props":{"cols":12,"gap":16,"padding":24},"children":[]}}',
  config_version  INTEGER       NOT NULL DEFAULT 1,
  status          SMALLINT      NOT NULL DEFAULT 0,        -- 0=draft / 1=published
  role_bindings   TEXT          NOT NULL DEFAULT '[]',     -- ['admin','operator',...]
  created_by      VARCHAR(64),
  created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at    TIMESTAMP
);
CREATE INDEX idx_portal_page_status ON sys_portal_page(status);
CREATE INDEX idx_portal_page_code   ON sys_portal_page(code);

-- ---------------------------------------------------------------------------
-- 2. sys_portal_block —— 内置组件字典
--    注：内置块由 PortalBlockSeeder @PostConstruct 幂等 seed（运行期插入更易演进 schema）；
--    迁移脚本只建表，不塞数据。
-- ---------------------------------------------------------------------------
CREATE TABLE sys_portal_block (
  id              BIGSERIAL       PRIMARY KEY,
  type            VARCHAR(64)   NOT NULL UNIQUE,
  name            VARCHAR(64)   NOT NULL,
  category        VARCHAR(32)   NOT NULL,                  -- stat / chart / table / media / layout
  default_config  TEXT          NOT NULL,
  config_schema   TEXT          NOT NULL,                  -- {fields:[{key,label,type,default,options?}]}
  icon            VARCHAR(64)   NOT NULL DEFAULT 'Grid',
  sort            INTEGER       NOT NULL DEFAULT 0,
  enabled         SMALLINT      NOT NULL DEFAULT 1,
  built_in        SMALLINT      NOT NULL DEFAULT 0,
  created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_portal_block_category ON sys_portal_block(category, enabled, sort);