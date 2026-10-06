-- V32: 循环任务精细化 —— WEEKLY/MONTHLY/YEARLY 配置具体日期
-- 新增 YEARLY 枚举值；为循环规则附加 3 个配置列（全部可空）。
-- WEEKLY  -> recurrence_dow (1-7, ISO：周一=1, 周日=7)
-- MONTHLY -> recurrence_dom (1-31, 0=最后一天)
-- YEARLY  -> recurrence_dom + recurrence_month (1-12)
-- DAILY/NONE：三列均为 NULL
--
-- 回填策略：「强制重新设置」（V28 之前的现有任务根据 due_at 推算）
-- 仅回填已存在且对应列 NULL 的任务；新创建的任务由前端表单保证非空。

ALTER TABLE sys_todo_task ADD COLUMN recurrence_dow   SMALLINT;
ALTER TABLE sys_todo_task ADD COLUMN recurrence_dom   SMALLINT;
ALTER TABLE sys_todo_task ADD COLUMN recurrence_month SMALLINT;

-- 回填 WEEKLY：PostgreSQL EXTRACT(DOW) 与 SQLite strftime('%w') 同样是 0=Sun..6=Sat，转换为 ISO 1=Mon..7=Sun
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

-- 回填 MONTHLY：直接从 due_at 取 day-of-month
UPDATE sys_todo_task
   SET recurrence_dom = EXTRACT(DAY FROM due_at)::int
 WHERE recurrence = 'MONTHLY' AND due_at IS NOT NULL AND recurrence_dom IS NULL;