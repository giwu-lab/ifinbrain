-- =====================================================================
-- V43: 把 sys_todo_tag (per-user) 转为 sys_dict_type='todo_tag' + sys_dict_item
-- 上下文：
--   1) 原 sys_todo_tag 表按 owner_id 隔离标签，每用户各自一套。
--   2) 任务表单让用户即时新建标签（todoTagApi.upsertByName）。
--   3) 字典管理项目要求"标签值与字典管理统一"——改成全局共享字典，
--      新建标签需去「系统设置 → 字典管理」增删改（dict:update 权限）。
--
-- 数据迁移步骤（顺序敏感）：
--   (1) 新增字典类型 'todo_tag'
--   (2) 把 sys_todo_tag 按 name dedup 灌入 sys_dict_item（label = name）
--   (3) 备份旧 sys_todo_task_tag（改名 _v42_backup）
--   (4) 重建 sys_todo_task_tag，FK 改指向 sys_dict_item.id
--   (5) 数据迁移：备份 → 新表（按 name 映射 dict_item.id）
--   (6) drop 备份表 + drop sys_todo_tag
-- =====================================================================

-- (1) 字典类型
INSERT INTO sys_dict_type (code, name, remark)
SELECT 'todo_tag', '任务标签', '任务管理可选标签（全员共享；新增需在字典管理维护）'
 WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE code = 'todo_tag');

-- (2) dedup 灌入 sys_dict_item
-- code 字段：name 归一化（小写 + 替换空格为 _），不超过 64 字符
-- label 字段：保留原 name 字符串
-- value 字段：与 label 同，兼容旧任务存字符串的 tagNames
INSERT INTO sys_dict_item (dict_id, code, label, value, sort, status)
SELECT
  (SELECT id FROM sys_dict_type WHERE code = 'todo_tag'),
  lower(replace(t.name, ' ', '_')),
  t.name,
  t.name,
  ROW_NUMBER() OVER (ORDER BY t.name),
  1
FROM (SELECT DISTINCT name FROM sys_todo_tag) t
WHERE NOT EXISTS (
  SELECT 1 FROM sys_dict_item di
   WHERE di.dict_id = (SELECT id FROM sys_dict_type WHERE code = 'todo_tag')
     AND di.label = t.name
);

-- (3) 备份旧中间表（rename 等价于脱离 FK，因为新表 _backup 不存在 = 旧 FK 失效）
ALTER TABLE sys_todo_task_tag RENAME TO _v42_sys_todo_task_tag_backup;

-- (4) 重建中间表，FK 指向 sys_dict_item
CREATE TABLE sys_todo_task_tag (
  task_id       INTEGER NOT NULL,
  dict_item_id  INTEGER NOT NULL,
  PRIMARY KEY (task_id, dict_item_id),
  FOREIGN KEY (task_id)      REFERENCES sys_todo_task(id)   ON DELETE CASCADE,
  FOREIGN KEY (dict_item_id) REFERENCES sys_dict_item(id)  ON DELETE CASCADE
);

-- (5) 数据迁移：备份 → 新表（按 name 映射到 dict_item.id）
INSERT INTO sys_todo_task_tag (task_id, dict_item_id)
SELECT bk.task_id, di.id
  FROM _v42_sys_todo_task_tag_backup bk
  JOIN sys_todo_tag tt ON tt.id = bk.tag_id
  JOIN sys_dict_item di
    ON di.label = tt.name
   AND di.dict_id = (SELECT id FROM sys_dict_type WHERE code = 'todo_tag');

-- (6) drop 备份 + drop sys_todo_tag
DROP TABLE IF EXISTS _v42_sys_todo_task_tag_backup;
DROP TABLE IF EXISTS sys_todo_tag;
