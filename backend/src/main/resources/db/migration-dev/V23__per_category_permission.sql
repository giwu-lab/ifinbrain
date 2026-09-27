-- V23: 类目权限升级 —— 每个类目生成专属 4 个权限码（view/upload/update/delete）。
-- 同步 sys_menu.permission 从 'documents:view' 改为 'documents:{slug}:view'，并 seed admin 角色。
--
-- 设计：
--  - 类目权限码格式 documents:{slug}:{view|upload|update|delete}
--  - admin 角色默认获得全部 4 个（保留原「admin 能管一切」行为）
--  - operator / viewer 不自动 seed —— 由 admin 在 UI 「权限」按钮显式分配
--  - slug 改名时旧 perm 全部解绑、新 perm 自动 seed admin（DocumentCategoryService.update）
--  - 类目删除时 4 个 perm 的角色绑定全删，sys_permission 行保留（幂等）

-- ---------------------------------------------------------------------------
-- 1. 为每个现存类目建 4 个权限码（已存在跳过 —— 幂等）
-- ---------------------------------------------------------------------------
INSERT INTO sys_permission (code, name, type)
SELECT 'documents:' || slug || ':view',
       name || ' 类目查看',
       'API'
  FROM sys_document_category
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code = 'documents:' || sys_document_category.slug || ':view');

INSERT INTO sys_permission (code, name, type)
SELECT 'documents:' || slug || ':upload',
       name || ' 类目上传',
       'API'
  FROM sys_document_category
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code = 'documents:' || sys_document_category.slug || ':upload');

INSERT INTO sys_permission (code, name, type)
SELECT 'documents:' || slug || ':update',
       name || ' 类目更新',
       'API'
  FROM sys_document_category
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code = 'documents:' || sys_document_category.slug || ':update');

INSERT INTO sys_permission (code, name, type)
SELECT 'documents:' || slug || ':delete',
       name || ' 类目删除',
       'API'
  FROM sys_document_category
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code = 'documents:' || sys_document_category.slug || ':delete');

-- ---------------------------------------------------------------------------
-- 2. 把类目菜单行的 permission 从 'documents:view' 改为 'documents:{slug}:view'
--    （仅作用于由 DocumentCategoryService 创建的类目菜单行 —— parent=50 + component=documents/list/Index）
-- ---------------------------------------------------------------------------
UPDATE sys_menu
   SET permission = (
     SELECT 'documents:' || c.slug || ':view'
       FROM sys_document_category c
      WHERE '/documents/' || c.slug = sys_menu.path
   )
 WHERE parent_id = 50
   AND component = 'documents/list/Index'
   AND permission = 'documents:view';

-- ---------------------------------------------------------------------------
-- 3. 给 admin 角色 seed 4 个新权限码（保留 admin 全访问语义）
--    仅 seed 之前不存在 的绑定 —— 幂等
-- ---------------------------------------------------------------------------
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'admin'
   AND p.code LIKE 'documents:%:view'
   AND p.code != 'documents:view'
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'admin'
   AND p.code LIKE 'documents:%:upload'
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'admin'
   AND p.code LIKE 'documents:%:update'
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'admin'
   AND p.code LIKE 'documents:%:delete'
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );