-- 数据管理三期清理：把"查询页面"列表入口从 sidebar 移除
-- V13 创建的 sys_menu id=43（path '/data-mgmt/query-pages'）由新的 query 列表内的操作列取代
-- 前端的 /data-mgmt/query-pages 路由已删除，所以这个菜单项点击会 404 —— 直接清掉
-- 同时把 data:query:publish perm 从 sys_menu 删（菜单已删，perm 留作 publish/unpublish/republish/delete 端点鉴权）

DELETE FROM sys_role_perm WHERE perm_id IN (
  SELECT id FROM sys_permission WHERE code = 'data:query:publish'
);
DELETE FROM sys_permission WHERE code = 'data:query:publish';
DELETE FROM sys_menu WHERE id = 43;

-- 重新加 data:query:publish perm（admin + operator 拿；用于 publish/unpublish/republish/delete 端点）
INSERT INTO sys_permission (code, name, type) VALUES
  ('data:query:publish', 'Publish query as page', 'API');
INSERT INTO sys_role_perm (role_id, perm_id)
  SELECT 1, id FROM sys_permission WHERE code = 'data:query:publish';
INSERT INTO sys_role_perm (role_id, perm_id)
  SELECT 2, id FROM sys_permission WHERE code = 'data:query:publish';