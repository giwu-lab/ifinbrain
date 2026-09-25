-- V19: 将 OCR 权限码 / 菜单归属统一改为「解析 (parser)」。
-- 背景：解析 API 页面已迁移到「发票管理」并重命名为「解析设置」。
--       权限码、菜单权限字段仍残留 OCR 字样， 语义已经不准， 现统一改为 parser。
-- 幂等：先做新键 → 再删旧键 (避免约束/外键冲突)。

-- 1) 新权限码 (幂等：已存在则跳过)
INSERT INTO sys_permission (code, name, type)
SELECT 'parser:view', 'Parser view', 'API'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'parser:view');

INSERT INTO sys_permission (code, name, type)
SELECT 'parser:update', 'Parser update', 'API'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'parser:update');

-- 2) 把 admin / operator / viewer 三个角色与新权限码绑定
--    (幂等：role_perm 没记录就插)
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code IN ('admin','operator')
   AND p.code IN ('parser:view','parser:update')
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'viewer'
   AND p.code = 'parser:view'
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

-- 3) 把菜单 (id=25) 的 permission 字段从 ocr:view 切到 parser:view
UPDATE sys_menu
   SET permission = 'parser:view'
 WHERE id = 25
   AND permission = 'ocr:view';

-- 4) 删除旧权限码 (以及对应的 role_perm)
DELETE FROM sys_role_perm
 WHERE perm_id IN (SELECT id FROM sys_permission WHERE code IN ('ocr:view','ocr:update'));

DELETE FROM sys_permission WHERE code IN ('ocr:view','ocr:update');