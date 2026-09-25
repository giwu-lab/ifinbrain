-- V10: Data Management feature (datasource CRUD + test connection)
-- Phase 1: 数据源管理（sys_datasource 表 + 4 个权限 + 顶级 DIR + 2 个子菜单）

CREATE TABLE sys_datasource (
  id              INTEGER      PRIMARY KEY AUTOINCREMENT,
  name            VARCHAR(64)  NOT NULL UNIQUE,
  db_type         VARCHAR(16)  NOT NULL,                -- 'mysql' | 'mssql' | 'postgresql'
  host            VARCHAR(128) NOT NULL,
  port            INTEGER      NOT NULL,
  database_name   VARCHAR(128) NOT NULL,
  username        VARCHAR(64)  NOT NULL,
  password_enc    TEXT         NOT NULL,                -- CryptoUtil.encrypt()
  remark          VARCHAR(255),
  status          SMALLINT     NOT NULL DEFAULT 1,
  last_test_ok    SMALLINT,                            -- 0=fail / 1=ok / NULL=未测
  last_test_at    TIMESTAMP,
  last_test_msg   VARCHAR(255),
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_datasource_type ON sys_datasource(db_type, status);

-- 权限（4 个独立粒度：view / update / delete / test）
INSERT INTO sys_permission (code, name, type) VALUES
  ('data:datasource:view',   'Datasource view',   'API'),
  ('data:datasource:update', 'Datasource update', 'API'),
  ('data:datasource:delete', 'Datasource delete', 'API'),
  ('data:datasource:test',   'Datasource test',   'API');

-- admin 全拿
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT 1, id FROM sys_permission WHERE code LIKE 'data:datasource:%';
-- operator 有 view/update/test（无 delete）
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT 2, id FROM sys_permission WHERE code IN ('data:datasource:view','data:datasource:update','data:datasource:test');
-- viewer 仅 view
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT 3, id FROM sys_permission WHERE code = 'data:datasource:view';

-- 顶级菜单：数据管理（sort=4，置于 系统设置 之前）
INSERT INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type, status) VALUES
  (40, 0, 'Data Management', '/data-mgmt', NULL, 'database', 4, 'data:datasource:view', 'DIR', 1);

-- 子菜单：数据源管理（一期）
INSERT INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type, status) VALUES
  (41, 40, 'Data Sources', '/data-mgmt/datasource', 'data-mgmt/datasource/Index', 'coin', 1, 'data:datasource:view', 'MENU', 1);

-- 子菜单：查询管理（二期占位）
INSERT INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type, status) VALUES
  (42, 40, 'Saved Queries', '/data-mgmt/query', 'data-mgmt/query/Index', 'search', 2, 'data:datasource:view', 'MENU', 1);