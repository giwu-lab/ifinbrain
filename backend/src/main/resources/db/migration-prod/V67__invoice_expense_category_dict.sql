-- V67: 发票费用类别字典
--
-- 背景：V66 把 fapiao_cn 的 expense_category_label 落到了 invoice_record，
-- 但实测发现解析侧**覆盖不全** —— 餐饮类能返「餐饮」，汽车销售这类返空串。
-- 用户需要在上传页人工补一个类别，所以把类别清单做成字典，在上传页渲染成下拉。
--
-- 为什么已经有列了还要字典：
--   列存的是**结果**（这一张发票被判成什么），字典存的是**候选集**（能选什么）。
--   两者职责不同：字典在「系统设置 → 字典管理」可由管理员增删改，解析侧升级加了
--   新类别也不用发版。
--
-- ⚠️ value 必须与解析侧返回的文案**逐字相同**（不能是 TRANSPORTATION 这种枚举码）：
--   上传页 el-select 直接 v-model 到 expenseCategoryLabel，解析返「餐饮」时
--   才能命中 value='餐饮' 的选项回显；value 若存枚举码，识别成功的发票反而会
--   显示成「字典里没有这个值」。
--   code 列保留小写英文枚举（V43 todo_tag 的先例：code=slug / label=value=显示名），
--   只作稳定标识用，不参与匹配。
--
-- 幂等：按 (dict_id, code) 判存在，可重复执行。

INSERT INTO sys_dict_type (code, name, remark)
SELECT 'invoice_expense_category',
       '发票费用类别',
       '上传页费用类别下拉的候选集；value 与 fapiao_cn 的 expense_category_label 逐字对应，勿改成枚举码'
 WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE code = 'invoice_expense_category');

INSERT INTO sys_dict_item (dict_id, code, label, value, sort, status)
SELECT d.id, v.code, v.label, v.label, v.sort, 1
FROM (VALUES
  ('transportation', '交通',   1),
  ('lodging',        '住宿',   2),
  ('dining',         '餐饮',   3),
  ('courier',        '快递',   4),
  ('telecom',        '通信',   5),
  ('water',          '水费',   6),
  ('electricity',    '电费',   7),
  ('gas',            '燃气',   8),
  ('fuel',           '成品油', 9),
  ('other',          '其他',  10)
) AS v(code, label, sort)
CROSS JOIN (SELECT id FROM sys_dict_type WHERE code = 'invoice_expense_category') d
WHERE NOT EXISTS (
  SELECT 1 FROM sys_dict_item di
   WHERE di.dict_id = d.id
     AND di.code = v.code
);