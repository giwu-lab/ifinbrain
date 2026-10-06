-- =====================================================================
-- V42: 取消发票「编辑」权限 + 显式收紧「删除」仅 admin
-- 上下文：
--   1) 发票列表 List.vue 删除编辑入口，PUT /api/invoices/{id} 服务端
--      controller/service 一并摘除，invoice:edit 权限码无调用方。
--   2) 角色权限：V2 已隐式建立 invoice:delete 只属于 admin
--      (SELECT 1, id FROM sys_permission)，本迁移用 WHERE NOT EXISTS
--      兜底给运维一份明确契约，同时 DELETE 防御历史误绑定。
-- =====================================================================

-- (1) 摘除 invoice:edit：清空所有角色绑定 → 删 sys_permission 行
DELETE FROM sys_role_perm
WHERE perm_id IN (SELECT id FROM sys_permission WHERE code = 'invoice:edit');

DELETE FROM sys_permission WHERE code = 'invoice:edit';

-- (2) 显式断言：invoice:delete 仅 admin 持有
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.code = 'admin'
  AND p.code = 'invoice:delete'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_perm rp WHERE rp.role_id = r.id AND rp.perm_id = p.id
  );

-- (3) 防御：剥夺任何非 admin 角色误持的 invoice:delete
DELETE FROM sys_role_perm
WHERE perm_id IN (SELECT id FROM sys_permission WHERE code = 'invoice:delete')
  AND role_id IN (SELECT id FROM sys_role WHERE code <> 'admin');