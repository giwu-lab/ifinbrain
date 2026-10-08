-- V51: 用户挂部门 + 组织架构菜单置顶
--
-- 两件事：
--   1. sys_user 增加 dept_id，用户归属某个部门
--   2. 组织架构菜单（id=27）从 sort=5 提到 sort=0，排到「用户管理」之前
--
-- 设计说明：
--   · dept_id 不建外键 —— 与既有 sys_user_role 等关联表保持一致（本库不用外键级联）。
--     代价是可能留下悬空 dept_id，由 SysDeptService.delete() 的引用校验兜住（409）。
--   · 菜单置顶用 sort=0 而不是把 1~6 整体后移：V21__category_settings_sort_zero.sql
--     已是本项目「置顶」的既定写法，且 sys_menu 上没有 (parent_id, sort) 唯一约束，
--     无需担心中间态撞唯一键。

-- ---------------------------------------------------------------------------
-- 1. sys_user.dept_id
-- ---------------------------------------------------------------------------
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS dept_id BIGINT;

-- 「某部门下有哪些人」的查询路径（用户管理页按部门筛选用得上）
CREATE INDEX IF NOT EXISTS idx_sys_user_dept_id ON sys_user(dept_id);

-- ---------------------------------------------------------------------------
-- 2. 组织架构菜单置顶
--    幂等：path 条件兜底，避免 id 被复用后误改别的菜单。
-- ---------------------------------------------------------------------------
UPDATE sys_menu SET sort = 0
 WHERE id = 27
   AND path = '/system/dept'
   AND sort <> 0;
