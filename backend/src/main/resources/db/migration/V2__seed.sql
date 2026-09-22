-- Seed data: admin user, roles, permissions, menus.
-- Password for 'admin' is 'Admin@123' (BCrypt cost=10).
INSERT INTO sys_user (username, password_hash, display_name, email, status)
VALUES ('admin',
        '$2b$10$NnQtUXBqaydAIqH/xsWJQ.dbsaQZkm23XGr56VNUdYW5wWw10epMm',
        'System Administrator',
        '[email protected]',
        1);

INSERT INTO sys_role (code, name, remark) VALUES
  ('admin',    'Administrator', 'Full access'),
  ('operator', 'Operator',      'Upload, view and edit invoices'),
  ('viewer',   'Viewer',        'Read-only');

INSERT INTO sys_permission (code, name, type) VALUES
  ('system:user:list',   'User list',      'API'),
  ('system:user:create', 'User create',    'API'),
  ('system:user:update', 'User update',    'API'),
  ('system:user:delete', 'User delete',    'API'),
  ('system:role:list',   'Role list',      'API'),
  ('system:role:create', 'Role create',    'API'),
  ('system:role:update', 'Role update',    'API'),
  ('system:role:delete', 'Role delete',    'API'),
  ('system:menu:list',   'Menu list',      'API'),
  ('system:menu:create', 'Menu create',    'API'),
  ('system:menu:update', 'Menu update',    'API'),
  ('system:menu:delete', 'Menu delete',    'API'),
  ('system:perm:list',   'Permission list','API'),
  ('invoice:list',       'Invoice list',   'API'),
  ('invoice:view',       'Invoice view',   'API'),
  ('invoice:upload',     'Invoice upload', 'API'),
  ('invoice:edit',       'Invoice edit',   'API'),
  ('invoice:delete',     'Invoice delete', 'API'),
  ('file:download',      'File download',  'API'),
  ('setting:view',       'Setting view',   'API'),
  ('setting:update',     'Setting update', 'API'),
  ('ocr:view',           'OCR view',       'API'),
  ('ocr:update',         'OCR update',     'API');

INSERT INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type, status) VALUES
  (1,  0, 'Dashboard',    '/dashboard',    'dashboard/Index',        'odometer',   1, NULL, 'MENU', 1),
  (10, 0, 'Invoice',      '/invoice',      NULL,                     'document',   2, NULL, 'DIR',  1),
  (11, 10,'Upload',       '/invoice/upload',  'invoice/Upload',       'upload',    10, NULL, 'MENU', 1),
  (12, 10,'Records',      '/invoice/list',    'invoice/List',         'list',      11, NULL, 'MENU', 1),
  (20, 0, 'System',       '/system',       NULL,                     'setting',    9, NULL, 'DIR',  1),
  (21, 20,'Users',        '/system/user',  'system/user/Index',      'user',       1, 'system:user:list',   'MENU', 1),
  (22, 20,'Roles',        '/system/role',  'system/role/Index',      'avatar',     2, 'system:role:list',   'MENU', 1),
  (23, 20,'Menus',        '/system/menu',  'system/menu/Index',      'menu',       3, 'system:menu:list',   'MENU', 1),
  (24, 20,'Storage',      '/system/storage','system/storage/Index',  'folder',     4, 'setting:view',       'MENU', 1),
  (25, 20,'OCR',          '/system/customurl','system/ocr/Index',     'magic-stick',5, 'ocr:view',           'MENU', 1);

INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT 1, id FROM sys_permission;

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT 2, id FROM sys_permission
WHERE code IN ('invoice:list','invoice:view','invoice:upload','invoice:edit','file:download');

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT 3, id FROM sys_permission
WHERE code IN ('invoice:list','invoice:view','file:download');

INSERT INTO sys_setting (key_name, value_text) VALUES
  ('storage.type', 'local'),
  ('storage.local.root', './data/storage');