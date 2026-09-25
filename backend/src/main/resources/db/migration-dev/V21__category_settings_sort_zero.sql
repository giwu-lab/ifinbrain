-- V21: 把 V20 引入的"类目设置"菜单 sort 调整为 0（永远排在最前）。
-- V20 已应用到 schema，因此这里用 UPDATE 而非 INSERT。
-- 幂等：多次执行结果一致。

UPDATE sys_menu SET sort = 0
 WHERE id = 100
   AND path = '/documents/category';
