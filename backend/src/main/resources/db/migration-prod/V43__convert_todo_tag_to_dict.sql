-- =====================================================================
-- V43 (prod / PostgreSQL): 把 sys_todo_tag (per-user) 转为 sys_dict_type='todo_tag' + sys_dict_item
-- 与 dev 同步；由 Flyway 的每条迁移事务保证原子性（脚本内不再自行 BEGIN/COMMIT）。
-- =====================================================================

-- (0) 对齐所有主键序列到 max(id)+1。
--
--     为什么必须在这里：V6 / V17 / V25 等 seed 迁移用**显式主键**插入了
--     sys_dict_type(1,2) / sys_menu(50,60,61,100) 等行，但没推进对应 sequence。
--     SQLite 的 INTEGER PRIMARY KEY AUTOINCREMENT 会自动同步 sqlite_sequence，
--     PostgreSQL 的 sequence 不会 —— 序列仍停在初始值 1。
--     于是本文件第 (1) 步那条**不指定 id** 的 INSERT 拿到 1 或 2，
--     直接撞 sys_dict_type_pkey（SQLSTATE 23505）。
--     本循环把每个 serial 主键列的序列推进到 max(id)+1 并置 is_called=false，
--     使下一个 nextval 正好返回 max(id)+1。
DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT c.table_name
          FROM information_schema.columns c
         WHERE c.table_schema = 'public'
           AND c.column_name = 'id'
           AND c.column_default LIKE 'nextval%'
    LOOP
        EXECUTE format(
            'SELECT setval(pg_get_serial_sequence(%L, ''id''), '
            'COALESCE((SELECT MAX(id) FROM %I), 0) + 1, false)',
            r.table_name, r.table_name);
    END LOOP;
END $$;

-- (1) 字典类型
INSERT INTO sys_dict_type (code, name, remark)
SELECT 'todo_tag', '任务标签', '任务管理可选标签（全员共享；新增需在字典管理维护）'
 WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE code = 'todo_tag');

-- (2) dedup 灌入 sys_dict_item
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

-- (3) 备份旧中间表
ALTER TABLE sys_todo_task_tag RENAME TO _v42_sys_todo_task_tag_backup;

-- (4) 重建中间表，FK 指向 sys_dict_item
CREATE TABLE sys_todo_task_tag (
  task_id       BIGINT NOT NULL,
  dict_item_id  BIGINT NOT NULL,
  PRIMARY KEY (task_id, dict_item_id),
  FOREIGN KEY (task_id)      REFERENCES sys_todo_task(id)   ON DELETE CASCADE,
  FOREIGN KEY (dict_item_id) REFERENCES sys_dict_item(id)  ON DELETE CASCADE
);

-- (5) 数据迁移：备份 → 新表
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
