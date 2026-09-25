-- V11: Data Management phase 2 (saved queries with custom SQL + safe execution)

CREATE TABLE sys_query (
  id                    INTEGER      PRIMARY KEY AUTOINCREMENT,
  name                  VARCHAR(128) NOT NULL,
  description           VARCHAR(512),
  datasource_id         INTEGER      NOT NULL,
  sql_template          TEXT         NOT NULL,
  params_schema_json    TEXT         NOT NULL DEFAULT '[]',
  status                SMALLINT     NOT NULL DEFAULT 1,
  last_run_ok           SMALLINT,
  last_run_at           TIMESTAMP,
  last_run_row_count    INTEGER,
  last_run_duration_ms  BIGINT,
  last_run_msg          VARCHAR(255),
  created_by            VARCHAR(64)  NOT NULL,
  created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_query_ds_name UNIQUE (datasource_id, name),
  CONSTRAINT fk_query_datasource FOREIGN KEY (datasource_id)
      REFERENCES sys_datasource(id) ON DELETE CASCADE
);

CREATE INDEX idx_query_datasource ON sys_query(datasource_id);
CREATE INDEX idx_query_status     ON sys_query(status);

-- 5 new permissions for query management
INSERT INTO sys_permission (code, name, type) VALUES
  ('data:query:view',   'Query view',   'API'),
  ('data:query:create', 'Query create', 'API'),
  ('data:query:update', 'Query update', 'API'),
  ('data:query:delete', 'Query delete', 'API'),
  ('data:query:run',    'Query run',    'API');

-- admin (role 1): all
INSERT INTO sys_role_perm (role_id, perm_id)
  SELECT 1, id FROM sys_permission WHERE code LIKE 'data:query:%';
-- operator (role 2): view + run (no modify)
INSERT INTO sys_role_perm (role_id, perm_id)
  SELECT 2, id FROM sys_permission WHERE code IN ('data:query:view','data:query:run');
-- viewer (role 3): view only
INSERT INTO sys_role_perm (role_id, perm_id)
  SELECT 3, id FROM sys_permission WHERE code = 'data:query:view';

-- Saved Queries menu perm: previously borrowed data:datasource:view; switch to data:query:view
UPDATE sys_menu SET permission = 'data:query:view' WHERE id = 42;