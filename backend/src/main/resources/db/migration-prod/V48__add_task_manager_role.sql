-- V48: 新增「任务管理者」预设角色（跨用户任务管理）
--
-- 背景：todo:task:view-all 这个「可查看所有人任务」的权限，此前是 V27 直接授予
-- admin 角色的。后果是仪表盘的「我的任务」卡片和 widget 也被带成了全员 PENDING 计数
-- （dashboard() 复用了同一个 view-all 判断），admin 明明只有 5 条自己的任务却显示 10。
--
-- 本次调整：
--   1. TodoTaskService.dashboard() 改为恒按 assigneeId = me 过滤 —— 「我的任务」就是
--      「我的任务」，不再因 view-all 混入别人的任务。全员视图只留给列表页 page()。
--   2. 把「可查看全员任务」这项能力显式化成一个预设角色 task_manager，
--      供非管理员的负责人使用。admin 角色保留 view-all（超级管理员），行为不变。
--   3. task_manager 列入 SystemPresets.PROTECTED_ROLE_CODES，不可删除 ——
--      删了「谁能看全员任务」这个能力就无处安放。
--
-- 权限范围：任务模块全套 + view-all + assign。
--   view-all 在本项目里是「读+写」语义（loadAndAuthorize / 附件删除都只看这一个位），
--   所以任务管理者能查看、完成、编辑、删除任何人的任务。
--   todo:task:view 同时是「任务清单」菜单的 permission，给了就能看到菜单入口。
--   刻意不授予发票/档案/数据/设计等其它模块权限 —— 任务管理者只管任务。
--
-- 幂等：全部用 NOT EXISTS / ON CONFLICT 风格守卫，重复执行不会报错或重复建。

INSERT INTO sys_role (code, name, remark, status)
SELECT 'task_manager', '任务管理者', '可查看并管理所有人的任务（跨用户任务管理）', 1
 WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE code = 'task_manager');

-- 授予任务模块权限
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'task_manager'
   AND p.code IN (
         'todo:task:view',        -- 任务清单菜单入口 + 读自己的任务
         'todo:task:create',
         'todo:task:update',
         'todo:task:delete',
         'todo:task:view-all',    -- 关键：跨用户查看 / 完成 / 编辑 / 删除
         'todo:task:assign',      -- 创建与重指派时可指定执行人
         'todo:attachment:upload',
         'todo:attachment:download',
         'todo:attachment:delete'
       )
   AND NOT EXISTS (
       SELECT 1 FROM sys_role_perm rp
        WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );
