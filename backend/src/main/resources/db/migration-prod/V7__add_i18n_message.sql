CREATE TABLE sys_i18n_message (
  id          BIGSERIAL    PRIMARY KEY,
  namespace   VARCHAR(32)   NOT NULL,
  code        VARCHAR(128)  NOT NULL,
  value_json  TEXT          NOT NULL,
  created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_i18n_ns_code UNIQUE (namespace, code)
);
CREATE INDEX idx_i18n_namespace ON sys_i18n_message(namespace);

INSERT INTO sys_permission (code, name, type) VALUES
  ('i18n:view',   'I18N view',   'API'),
  ('i18n:update', 'I18N update', 'API'),
  ('i18n:delete', 'I18N delete', 'API');

INSERT INTO sys_role_perm (role_id, perm_id)
  SELECT 1, p.id FROM sys_permission p WHERE p.code IN ('i18n:view','i18n:update','i18n:delete')
  AND NOT EXISTS (SELECT 1 FROM sys_role_perm rp WHERE rp.role_id = 1 AND rp.perm_id = p.id);