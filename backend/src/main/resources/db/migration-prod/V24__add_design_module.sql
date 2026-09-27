-- V24: 设计开发（Design Development）顶级模块 —— 占位骨架
-- 本次仅加菜单 + 权限 + 角色绑定；不建业务表。
-- 后续 V25+ 再上 sys_portal_page / sys_portal_block（页面设计器）
-- 流程设计暂列后期计划。

-- ---------------------------------------------------------------------------
-- 1. 8 个权限码
-- ---------------------------------------------------------------------------
INSERT INTO sys_permission (code, name, type)
SELECT 'design:page:view',    'Page designer view',    'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'design:page:view');

INSERT INTO sys_permission (code, name, type)
SELECT 'design:page:update',  'Page designer update',  'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'design:page:update');

INSERT INTO sys_permission (code, name, type)
SELECT 'design:page:delete',  'Page designer delete',  'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'design:page:delete');

INSERT INTO sys_permission (code, name, type)
SELECT 'design:page:publish', 'Page designer publish', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'design:page:publish');

INSERT INTO sys_permission (code, name, type)
SELECT 'design:flow:view',    'Flow designer view',    'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'design:flow:view');

INSERT INTO sys_permission (code, name, type)
SELECT 'design:flow:update',  'Flow designer update',  'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'design:flow:update');

INSERT INTO sys_permission (code, name, type)
SELECT 'design:flow:delete',  'Flow designer delete',  'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'design:flow:delete');

INSERT INTO sys_permission (code, name, type)
SELECT 'design:flow:enable',  'Flow designer enable',  'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'design:flow:enable');

-- ---------------------------------------------------------------------------
-- 2. 角色绑定：admin 全 8 个；operator / viewer 仅 view x2
-- ---------------------------------------------------------------------------
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'admin'
   AND p.code LIKE 'design:%'
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code IN ('operator', 'viewer')
   AND p.code IN ('design:page:view', 'design:flow:view')
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

-- ---------------------------------------------------------------------------
-- 3. 顶级菜单 DIR：设计开发（id=30, sort=3 —— 介于 Invoice=2 与 DataMgmt=4 之间）
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type, status)
SELECT 30, 0, 'Design Development', '/design', NULL, 'MagicStick', 3, 'design:page:view', 'DIR', 1
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 30);

-- ---------------------------------------------------------------------------
-- 4. 子菜单 MENU：页面设计（id=31）
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type, status)
SELECT 31, 30, 'Page Designer', '/design/page', 'design/page/Index', 'Files', 1, 'design:page:view', 'MENU', 1
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 31);

-- ---------------------------------------------------------------------------
-- 5. 子菜单 MENU：流程设计（id=32，后期计划占位）
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type, status)
SELECT 32, 30, 'Flow Designer', '/design/flow', 'design/flow/Index', 'Share', 2, 'design:flow:view', 'MENU', 1
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 32);