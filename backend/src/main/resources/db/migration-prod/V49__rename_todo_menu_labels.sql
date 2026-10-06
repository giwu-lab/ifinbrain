-- V49: 任务模块菜单更名（顶级目录与二级菜单对调）
--
-- 背景：V27 建模块时，顶级目录叫 "Task List"、二级菜单叫 "Task Management"，
-- 中文对应「任务清单 / 任务管理」。语义是反的 —— 目录是"任务"这一整块的容器，
-- 目录里那个页面才是具体那份清单。
--
-- 本次对调：
--   id=60 (DIR)  'Task List'       -> 'Task Management'
--   id=61 (MENU) 'Task Management' -> 'Task List'
--
-- 为什么改 DB：sys_menu.name 只是 i18n 未命中时的兜底显示名 —— 侧边栏实际文案取自
--   前端 i18n 的 menu.todo._（目录）和 menu.todo.task.Index（二级），见
--   SidebarLayout.vue 的 menuName() 三级回退。本次已同步改 frontend/src/i18n/locales
--   的 zh-CN.ts / en-US.ts；这里改 DB 是为了让「系统设置 → 菜单管理」列表页和任何
--   仍读原始 name 的地方保持一致。
--
-- 不动的东西：path / component / permission / sort / icon / type 全部原样保留，
--   路由匹配和鉴权完全不受影响（component 仍是 todo/task/Index，path 仍是 /todo）。
--
-- 幂等：两条 UPDATE 都带"旧值"条件。对调必须写成两条语句并各自用 id 限定，
--   否则第二条会命中刚被第一条改掉的第 60 行。管理员若在「菜单管理」里自定义过
--   名称，旧值不匹配则本迁移不覆盖其改动。

UPDATE sys_menu SET name = 'Task Management' WHERE id = 60 AND name = 'Task List';
UPDATE sys_menu SET name = 'Task List'       WHERE id = 61 AND name = 'Task Management';
