-- V33: 单独回填 sys_todo_task 的 recurrence 配置字段
-- V32 已加列但其内嵌的 UPDATE 因毫秒时间戳格式问题未生效；本次用正确格式补回填。
-- （保留 V32 的 ALTER 让 schema_version 序列连续；本文件只做幂等 UPDATE）

-- WEEKLY -> recurrence_dow (ISO: 1=Mon..7=Sun)
-- due_at 是真正的 TIMESTAMP 列（见 V27），直接 EXTRACT 即可 —— 无需 SQLite 的毫秒时间戳换算
UPDATE sys_todo_task
   SET recurrence_dow = CASE EXTRACT(DOW FROM due_at)::int
                          WHEN 0 THEN 7
                          WHEN 1 THEN 1
                          WHEN 2 THEN 2
                          WHEN 3 THEN 3
                          WHEN 4 THEN 4
                          WHEN 5 THEN 5
                          WHEN 6 THEN 6
                        END
 WHERE recurrence = 'WEEKLY' AND due_at IS NOT NULL AND recurrence_dow IS NULL;

-- MONTHLY -> recurrence_dom (1-31)
UPDATE sys_todo_task
   SET recurrence_dom = EXTRACT(DAY FROM due_at)::int
 WHERE recurrence = 'MONTHLY' AND due_at IS NOT NULL AND recurrence_dom IS NULL;