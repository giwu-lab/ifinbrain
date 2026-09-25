-- V20: 把"类目设置"做成档案管理 DIR 下的固定子菜单。
-- 之前 /documents/category 是 static fallback；现在升级为真菜单项（始终可见），
-- sort=1 排在用户运行时创建的所有类目子菜单前面。

INSERT INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type, status) VALUES
  (100, 50, 'Category Settings', '/documents/category', 'documents/category/Index', 'Setting', 0, 'documents:view', 'MENU', 1);
