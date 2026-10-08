-- V56: 任务模块读写权限拆分（view-all 只读 + manage-all 限本部门写）
--
-- 背景
--   此前 todo:task:view-all 同时承担读与写。TodoTaskService.loadAndAuthorize() 的
--   admin 分支对读（detail）和写（update / complete / delete）一视同仁放行，
--   附件侧 delete() 的 isAssigneeOrAdmin 判断同样只看这一个位。后果是
--   task_manager 能编辑、完成、**硬删除**任何部门的任务。
--   更隐蔽的是 loadAndAuthorize 里两个 filter 条件完全相同，
--   requireAssignable 参数是死代码，注释宣称的"要求 assignee=me"从未真正生效。
--
-- 本次拆分
--   todo:task:view-all    跨部门**只读** —— 可见全员（含跨部门）任务
--   todo:task:manage-all  **限本部门子树内可写**，判定见 TodoTaskService.canWrite：
--                          assignee 是自己，或 manage-all 且执行人部门 ∈ 我的部门子树。
--                          admin 角色豁免部门约束；未挂部门的用户 fail-closed。
--
-- 迁移策略（重要）
--   给所有**当前持有** view-all 的角色补发 manage-all，使上线瞬间行为零变化 ——
--   否则 task_manager 会突然失去对他人任务的写权限，属于破坏性变更。
--   之后若要把某个角色降级为纯只读，单独回收它的 manage-all 即可，代码侧无需再动。
--   这正是拆分权限位的意义：读与写从此可以独立调度。
--
-- 幂等：全部 NOT EXISTS / 条件 UPDATE 守卫，重复执行无副作用。

-- (1) 新增写权限位
INSERT INTO sys_permission (code, name, type)
SELECT 'todo:task:manage-all', 'Manage tasks within own department', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'todo:task:manage-all');

-- (2) view-all 名称改为体现"只读"语义。
--     sys_permission 只有 (id, code, name, type, created_at)，没有 remark 列可写。
UPDATE sys_permission
   SET name = 'View all tasks (read-only, cross-dept)'
 WHERE code = 'todo:task:view-all'
   AND name <> 'View all tasks (read-only, cross-dept)';

-- (3) 补发：所有当前持 view-all 的角色一并获得 manage-all。
--     从 sys_role_perm 自连接推导，角色增删后重跑本迁移也能补齐。
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT rp.role_id, pm.id
  FROM sys_role_perm rp
  JOIN sys_permission pv ON pv.id = rp.perm_id
  JOIN sys_permission pm ON pm.code = 'todo:task:manage-all'
 WHERE pv.code = 'todo:task:view-all'
   AND NOT EXISTS (
       SELECT 1 FROM sys_role_perm rp2
        WHERE rp2.role_id = rp.role_id AND rp2.perm_id = pm.id
   );
