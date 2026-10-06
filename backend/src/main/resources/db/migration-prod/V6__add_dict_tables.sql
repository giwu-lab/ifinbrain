-- Dictionary feature: dict types + dict items
CREATE TABLE sys_dict_type (
  id          BIGSERIAL      PRIMARY KEY,
  code        VARCHAR(64)  NOT NULL UNIQUE,
  name        VARCHAR(128) NOT NULL,
  remark      VARCHAR(255),
  status      SMALLINT     NOT NULL DEFAULT 1,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_dict_item (
  id          BIGSERIAL      PRIMARY KEY,
  dict_id     BIGINT      NOT NULL,
  parent_id   BIGINT      NOT NULL DEFAULT 0,
  code        VARCHAR(64)  NOT NULL,
  label       VARCHAR(128) NOT NULL,
  value       VARCHAR(255),
  sort        INTEGER      NOT NULL DEFAULT 0,
  status      SMALLINT     NOT NULL DEFAULT 1,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_dict_item_type FOREIGN KEY (dict_id) REFERENCES sys_dict_type(id) ON DELETE CASCADE,
  CONSTRAINT uq_dict_item_code UNIQUE (dict_id, code)
);

CREATE INDEX idx_dict_item_dict ON sys_dict_item(dict_id, status, sort);

INSERT INTO sys_permission (code, name, type) VALUES
  ('dict:view',   'Dictionary view',   'API'),
  ('dict:update', 'Dictionary update', 'API'),
  ('dict:delete', 'Dictionary delete', 'API');

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT 1, id FROM sys_permission WHERE code LIKE 'dict:%';
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT 2, id FROM sys_permission WHERE code = 'dict:view';
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT 3, id FROM sys_permission WHERE code = 'dict:view';

INSERT INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type, status) VALUES
  (26, 20, 'Dictionaries', '/system/dict', 'system/dict/Index', 'collection', 6, 'dict:view', 'MENU', 1);

INSERT INTO sys_dict_type (id, code, name, remark) VALUES
  (1, 'invoice_status', '发票状态', '识别结果状态枚举'),
  (2, 'file_format',    '文件格式', '上传文件类型');

INSERT INTO sys_dict_item (dict_id, code, label, value, sort) VALUES
  (1, 'success',    '识别成功', 'success',    1),
  (1, 'processing', '处理中',   'processing', 2),
  (1, 'error',      '识别失败', 'error',      3),
  (1, 'empty',      '未识别',   'empty',      4),
  (2, 'pdf', 'PDF', 'pdf', 1),
  (2, 'xml', 'XML', 'xml', 2),
  (2, 'ofd', 'OFD', 'ofd', 3);