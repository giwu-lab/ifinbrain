-- 数据管理三期：把 saved query 一键发布为列表页
-- sys_menu 加 query_id 列（nullable）：NULL = 普通菜单；非 NULL = 发布的查询页
-- 发布的页面用单一组件 data-mgmt/query-page/Index，通过 URL /data-mgmt/query-page/{menuId} 解析

-- ====== 清理 V12 残留（v2 Report Design 表 + perms） ======
DROP TABLE IF EXISTS sys_report;
DELETE FROM sys_role_perm WHERE perm_id IN (SELECT id FROM sys_permission WHERE code LIKE 'data:report:%');
DELETE FROM sys_permission WHERE code LIKE 'data:report:%';
-- 把 menu id=43 还原成 V13 的内容（V12 写过一遍"Report Design"）

-- ====== 新 schema ======
ALTER TABLE sys_menu ADD COLUMN query_id INTEGER;
CREATE INDEX idx_sys_menu_query_id ON sys_menu(query_id) WHERE query_id IS NOT NULL;

-- 新 perm：data:query:publish（admin + operator 拿；viewer 不拿）
INSERT INTO sys_permission (code, name, type) VALUES
  ('data:query:publish', 'Publish query as page', 'API');

INSERT INTO sys_role_perm (role_id, perm_id)
  SELECT 1, id FROM sys_permission WHERE code = 'data:query:publish';
INSERT INTO sys_role_perm (role_id, perm_id)
  SELECT 2, id FROM sys_permission WHERE code = 'data:query:publish';

-- 重写 menu id=43 指向"查询页面"列表入口
UPDATE sys_menu
   SET name='Query Pages',
       path='/data-mgmt/query-pages',
       component='data-mgmt/query-pages/Index',
       permission='data:query:publish',
       sort=4
 WHERE id=43;