-- V57: 任务预估时长（estimate_minutes）
--
-- 背景
--   仪表盘「我的任务」改造成四象限视图后，任务卡片需要展示"预估投入时长"
--   （如 45 分钟 / 1 小时 30 分钟），象限头还需要一个"建议投入时长"作为行动提示。
--   原 sys_todo_task 只有 due_at（什么时候之前），没有"要花多久"的概念。
--
-- 设计
--   · 可空。存量 31 条任务全部为 NULL，前端据此不渲染时长元素，不会显示成 "0分钟"。
--   · 单位为分钟而非时间字符串：便于按象限做时长汇总（未来「洞察」tab 会用到），
--     显示层的 "1小时30分钟" 由前端格式化，不在库里存冗余文本。
--   · CHECK 约束只挡负数（0 表示"已估但为 0"，无意义但无害，放行以免历史数据回填被卡）。
--
-- 幂等：IF NOT EXISTS + 条件 ALTER，重复执行无副作用。

ALTER TABLE sys_todo_task
    ADD COLUMN IF NOT EXISTS estimate_minutes INTEGER;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_todo_task_estimate_minutes'
    ) THEN
        ALTER TABLE sys_todo_task
            ADD CONSTRAINT ck_todo_task_estimate_minutes
            CHECK (estimate_minutes IS NULL OR estimate_minutes >= 0);
    END IF;
END $$;

COMMENT ON COLUMN sys_todo_task.estimate_minutes IS
    '预估投入时长（分钟）。NULL = 未估，前端不显示时长。与 due_at（截止时间）语义不同。';
