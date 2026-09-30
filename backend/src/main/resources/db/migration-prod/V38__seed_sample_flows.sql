-- V38: 预置 3 个示例流程（admin 即可审批，便于演示 + 学习）
--
-- 注：
--   - SpEL 条件引用变量用 ${vars.x} 格式（ConditionEvaluator.evaluate 注入 vars 变量）
--   - approvalType=OR 单签模式：取 userIds[0] 建一个任务（Phase 4.x 强化会签）
--   - approvalType=AND 会签模式：当前 Phase 4 简化为 userIds[0]（与 OR 等同），后续扩展

-- 1. 请假流程示例：start → admin 直属主管 → end
INSERT INTO sys_flow_define (code, name, description, flow_json, default_form_code, status, version, created_by)
SELECT 'leave-sample', '请假审批示例', 'admin 直接审批',
       '{"nodes":[
         {"id":"n_start","data":{"type":"START","label":"发起申请"}},
         {"id":"n1","data":{"type":"APPROVAL","label":"直属主管","userIds":["admin"],"roleIds":[],"orgIds":[],"approvalType":"OR"}},
         {"id":"n_end","data":{"type":"END","label":"结束"}}
       ],"edges":[
         {"id":"e1","source":"n_start","target":"n1","data":{"condition":""}},
         {"id":"e2","source":"n1","target":"n_end","data":{"condition":"approved"}}
       ]}'  , NULL, 1, 1, 'system'
 WHERE NOT EXISTS (SELECT 1 FROM sys_flow_define WHERE code = 'leave-sample');

-- 2. 报销流程示例：start → admin → 条件(${vars.amount > 5000}) → admin 总监 / end
INSERT INTO sys_flow_define (code, name, description, flow_json, default_form_code, status, version, created_by)
SELECT 'expense-sample', '报销审批示例', 'SpEL 条件分支：vars.amount > 5000',
       '{"nodes":[
         {"id":"n_start","data":{"type":"START","label":"发起报销"}},
         {"id":"n1","data":{"type":"APPROVAL","label":"直属主管","userIds":["admin"],"roleIds":[],"orgIds":[],"approvalType":"OR"}},
         {"id":"n_cond","data":{"type":"CONDITION","label":"金额>5000?","branches":[{"label":"是","condition":"${vars.amount > 5000}"},{"label":"否","condition":""}]}},
         {"id":"n_director","data":{"type":"APPROVAL","label":"总监","userIds":["admin"],"roleIds":[],"orgIds":[],"approvalType":"OR"}},
         {"id":"n_end","data":{"type":"END","label":"结束"}}
       ],"edges":[
         {"id":"e1","source":"n_start","target":"n1","data":{"condition":""}},
         {"id":"e2","source":"n1","target":"n_cond","data":{"condition":"approved"}},
         {"id":"e3","source":"n_cond","target":"n_director","data":{"condition":"${vars.amount > 5000}"}},
         {"id":"e4","source":"n_cond","target":"n_end","data":{"condition":""}},
         {"id":"e5","source":"n_director","target":"n_end","data":{"condition":"approved"}}
       ]}'  , NULL, 1, 1, 'system'
 WHERE NOT EXISTS (SELECT 1 FROM sys_flow_define WHERE code = 'expense-sample');

-- 3. 项目立项示例：start → admin 会签组 → end（AND 模式当前实现同 OR：userIds[0]）
INSERT INTO sys_flow_define (code, name, description, flow_json, default_form_code, status, version, created_by)
SELECT 'project-sample', '项目立项示例', '会签模式（AND；当前实现同 OR，Phase 4.x 强化）',
       '{"nodes":[
         {"id":"n_start","data":{"type":"START","label":"发起立项"}},
         {"id":"n1","data":{"type":"APPROVAL","label":"会签组","userIds":["admin"],"roleIds":[],"orgIds":[],"approvalType":"AND"}},
         {"id":"n_end","data":{"type":"END","label":"结束"}}
       ],"edges":[
         {"id":"e1","source":"n_start","target":"n1","data":{"condition":""}},
         {"id":"e2","source":"n1","target":"n_end","data":{"condition":"approved"}}
       ]}'  , NULL, 1, 1, 'system'
 WHERE NOT EXISTS (SELECT 1 FROM sys_flow_define WHERE code = 'project-sample');