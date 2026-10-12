-- V68: 恢复被误删的「报销申请表单」字典明细
--
-- 现象：sys_dict_type.code='invoice_reimburse_form' 还在，但它的明细被删空了
--       （字典管理 → 报销申请表单 → 字典条目 列表为空，itemCount=0）。
--
-- 影响：功能没坏 —— ReimburseDialog.resolveFormCode() 读不到字典时会退回
--       硬编码 FALLBACK_FORM_CODE='expense-apply'，恰好就是被删那条指向的表单，
--       所以报销弹窗照常渲染。坏的是「可配置性」：V63 之所以把表单绑定做成字典，
--       就是为了让管理员改绑定不用发版（见 V63 注释）；明细一空，这个能力静默失效，
--       换表单就得改代码重新发版，而且界面上没有任何提示。
--
-- ⚠️ 判存在条件必须是 (dict_id, code='default')，不能用「该字典一条都没有」：
--   管理员改绑定的方式正是**编辑**这条 code='default' 的记录、把 value 换成别的表单，
--   此时 default 行仍在。若按「空才补」判断，会把管理员刚配好的绑定覆盖回
--   expense-apply。按 code 判断则两种情况都对：
--     - 明细被误删 → default 行不存在 → 补回（本次就是这种情况）
--     - 管理员改过绑定 → default 行存在 → NOT EXISTS 命中，什么都不做
--
-- 幂等：可重复执行。

-- (1) 字典类型自愈（正常情况下已存在；万一整个类型被删也一并重建）
INSERT INTO sys_dict_type (code, name, remark)
SELECT 'invoice_reimburse_form',
       '报销申请表单',
       '发票报销弹窗动态加载的表单 code（对应 sys_form_design.code）；改这里即换表单，无需发版'
 WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE code = 'invoice_reimburse_form');

-- (2) 恢复 default 明细
INSERT INTO sys_dict_item (dict_id, code, label, value, sort, status)
SELECT (SELECT id FROM sys_dict_type WHERE code = 'invoice_reimburse_form'),
       'default', '默认报销申请单', 'expense-apply', 1, 1
 WHERE NOT EXISTS (
   SELECT 1 FROM sys_dict_item
    WHERE dict_id = (SELECT id FROM sys_dict_type WHERE code = 'invoice_reimburse_form')
      AND code = 'default'
 );