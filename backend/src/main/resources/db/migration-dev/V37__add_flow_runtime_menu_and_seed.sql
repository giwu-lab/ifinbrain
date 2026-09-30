-- V37: 新增「流程运行」菜单 + 修改 leave-flow 审批人为 admin
--
-- 1. 新增菜单 id=34「流程运行」—— 复用 design:flow:deploy perm（admin 已有）
-- 2. 修改现有 leave-flow 审批人 u_001 → admin（admin 即可直接审批 + 演示）

-- 1. sys_menu 新增 id=34「流程运行」
INSERT INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type, status)
SELECT 34, 30, 'Flow Runtime', '/design/flow-runtime', 'design/flow/Workflow',
       'Promotion', 3, 'design:flow:deploy', 'MENU', 1
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 34);

-- 2. 把现有 leave-flow 的审批人 u_001 改成 admin（admin 即可审批）
UPDATE sys_flow_define
   SET flow_json = REPLACE(flow_json, '"u_001"', '"admin"')
 WHERE code = 'leave-flow';