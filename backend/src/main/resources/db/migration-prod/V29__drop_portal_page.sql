-- V29: 硬删 portal_page —— 设计开发 v2 重写
-- 设计模块不再使用自由布局门户（portal_page / portal_block）。
-- 后续 V30+ 表单设计器 + 流程设计器替代。
--
-- 本次清理：
--   1. DROP sys_portal_block / sys_portal_page 两张表
--   2. 删除 4 个 design:page:* 权限 + 角色绑定
--   3. 删除 Page Designer 子菜单（id=31）
--   4. 保留 Design Development DIR（id=30）和 Flow Designer 子菜单（id=32）

-- ---------------------------------------------------------------------------
-- 1. DROP 两张表
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS sys_portal_block;
DROP TABLE IF EXISTS sys_portal_page;

-- ---------------------------------------------------------------------------
-- 2. 清理 4 个 design:page:* 权限 + 角色绑定
-- ---------------------------------------------------------------------------
DELETE FROM sys_role_perm
 WHERE perm_id IN (SELECT id FROM sys_permission WHERE code LIKE 'design:page:%');

DELETE FROM sys_permission WHERE code LIKE 'design:page:%';

-- ---------------------------------------------------------------------------
-- 3. 删除 Page Designer 子菜单（id=31）
--    保留 Design Development DIR（id=30）和 Flow Designer（id=32）。
-- ---------------------------------------------------------------------------
DELETE FROM sys_menu WHERE id = 31;