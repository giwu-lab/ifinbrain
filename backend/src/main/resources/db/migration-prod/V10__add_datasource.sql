-- V10: Data Management feature (datasource CRUD + test connection)

CREATE TABLE sys_datasource (
  id              BIGSERIAL      PRIMARY KEY,
  name            VARCHAR(64)  NOT NULL UNIQUE,
  db_type         VARCHAR(16)  NOT NULL,
  host            VARCHAR(128) NOT NULL,
  port            INTEGER      NOT NULL,
  database_name   VARCHAR(128) NOT NULL,
  username        VARCHAR(64)  NOT NULL,
  password_enc    TEXT         NOT NULL,
  remark          VARCHAR(255),
  status          SMALLINT     NOT NULL DEFAULT 1,
  last_test_ok    SMALLINT,
  last_test_at    TIMESTAMP,
  last_test_msg   VARCHAR(255),
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_datasource_type ON sys_datasource(db_type, status);

INSERT INTO sys_permission (code, name, type) VALUES
  ('data:datasource:view',   'Datasource view',   'API'),
  ('data:datasource:update', 'Datasource update', 'API'),
  ('data:datasource:delete', 'Datasource delete', 'API'),
  ('data:datasource:test',   'Datasource test',   'API');

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT 1, id FROM sys_permission WHERE code LIKE 'data:datasource:%';
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT 2, id FROM sys_permission WHERE code IN ('data:datasource:view','data:datasource:update','data:datasource:test');
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT 3, id FROM sys_permission WHERE code = 'data:datasource:view';

INSERT INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type, status) VALUES
  (40, 0, 'Data Management', '/data-mgmt', NULL, 'database', 4, 'data:datasource:view', 'DIR', 1);

INSERT INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type, status) VALUES
  (41, 40, 'Data Sources', '/data-mgmt/datasource', 'data-mgmt/datasource/Index', 'coin', 1, 'data:datasource:view', 'MENU', 1);

INSERT INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type, status) VALUES
  (42, 40, 'Saved Queries', '/data-mgmt/query', 'data-mgmt/query/Index', 'search', 2, 'data:datasource:view', 'MENU', 1);