-- V22: 自愈式保护 - 如果"类目设置"菜单行（id=100, path=/documents/category）不存在，重新插入。
-- 触发场景：手 SQL 误删 / 数据迁移丢失。
-- 幂等：WHERE NOT EXISTS 保护，多次执行结果一致。

INSERT INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type, status)
SELECT 100, 50, 'Category Settings', '/documents/category', 'documents/category/Index', 'Setting', 0, 'documents:view', 'MENU', 1
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 100);
