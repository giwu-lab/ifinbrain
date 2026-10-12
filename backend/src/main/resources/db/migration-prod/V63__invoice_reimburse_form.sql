-- V57: 发票报销申请单（动态表单对接）
--
-- 背景：上传识别页点「报销」时，除了固定的报销单号 / 报销人 / 报销日期三件套
-- （它们决定 storageKey 目录与审计口径，不能交给表单设计器），还需要一张
-- **可由管理员随时改布局**的「报销申请单」。
--
-- 本迁移做三件事：
--   1. invoice_record 加 form_data TEXT —— 存申请单字段值（JSON 字符串）
--   2. 字典 invoice_reimburse_form —— 单条记录，value = 绑定的表单 code
--   3. seed 一份默认的 expense-apply 表单（status=1 已发布）
--
-- 为什么表单 code 走字典而不是写死常量：
--   管理员可在「系统设置 → 字典管理」改绑定，换表单不用发版、多环境可各配各的。
--
-- ---------------------------------------------------------------------------
-- 1. form_data 列
-- ---------------------------------------------------------------------------
-- 约定：TEXT 存 JSON 字符串，不建 JSONB —— 与 sys_form_design.fields_json /
-- sys_flow_instance.variables_json 全库惯例一致（JPA 侧统一 @JsonIgnore，
-- 由 InvoiceGroupDto 解析成 Map 再出接口，避免裸 JSON 串漏给前端）。
ALTER TABLE invoice_record
  ADD COLUMN IF NOT EXISTS form_data TEXT;

COMMENT ON COLUMN invoice_record.form_data IS '报销申请单字段值（JSON 字符串，key = 表单字段 key）';

-- ---------------------------------------------------------------------------
-- 2. 字典：报销单绑定哪张表单
-- ---------------------------------------------------------------------------
INSERT INTO sys_dict_type (code, name, remark)
SELECT 'invoice_reimburse_form', '报销申请表单', '发票报销弹窗动态加载的表单 code（对应 sys_form_design.code）；改这里即换表单，无需发版'
 WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE code = 'invoice_reimburse_form');

-- 单条字典项：code 固定为 default，value 才是真正生效的表单 code
INSERT INTO sys_dict_item (dict_id, code, label, value, sort, status)
SELECT (SELECT id FROM sys_dict_type WHERE code = 'invoice_reimburse_form'),
       'default', '默认报销申请单', 'expense-apply', 1, 1
 WHERE NOT EXISTS (
   SELECT 1 FROM sys_dict_item
    WHERE dict_id = (SELECT id FROM sys_dict_type WHERE code = 'invoice_reimburse_form')
      AND code = 'default'
 );

-- ---------------------------------------------------------------------------
-- 3. seed 默认报销申请单 expense-apply
-- ---------------------------------------------------------------------------
-- ⚠️ 两个必须遵守的形状约束（FormDesignService.parseFields:208 起）：
--   a) fields_json 必须是**裸数组** —— 代码按 List<FormFieldSchema> 读，
--      V31 的表注释里写的 {"version":1,"fields":[...]} 包装形状会让解析直接抛错。
--   b) type 必须**全大写** —— 对应 Java 枚举 FieldType.TEXT / NUMBER / ...
--   ⚠️ 这两条错了不会报错：parseFields 吞掉异常只 log.warn 后返回空列表，
--      接口照样 200，但表单渲染成空白。所以下面对 type 全大写。
--
-- DIVIDER 也必须给 key（validateFields:147 对所有类型统一校验 key 非空且唯一）。
INSERT INTO sys_form_design (code, name, description, fields_json, status, created_by)
SELECT 'expense-apply',
       '报销申请单',
       '发票报销弹窗的动态申请单。布局可在本页直接编辑保存，弹窗下次打开即生效',
       $json$[
  {"id":"d1","type":"DIVIDER","key":"sec_items","label":"费用明细","fullWidth":true},
  {"id":"f1","type":"TABLE","key":"expenseItems","label":"费用明细","required":true,"requireAllCells":true,"fullWidth":true,
   "columns":[
     {"key":"name","label":"费用项目","type":"TEXT","required":true,"width":200},
     {"key":"category","label":"类别","type":"SELECT","required":true,"width":140,
      "options":[{"label":"交通","value":"transport"},{"label":"住宿","value":"hotel"},{"label":"餐饮","value":"meal"},{"label":"办公","value":"office"},{"label":"其他","value":"other"}]},
     {"key":"amount","label":"金额","type":"NUMBER","required":true,"width":120}
   ]},
  {"id":"d2","type":"DIVIDER","key":"sec_info","label":"报销信息","fullWidth":true},
  {"id":"f2","type":"DATE","key":"expenseDate","label":"费用发生日期","required":true,"fullWidth":false},
  {"id":"f3","type":"TEXT","key":"applyNo","label":"内部申请编号","required":true,"validator":{"min":1,"max":64},"placeholder":"企业内部申请单号","fullWidth":false},
  {"id":"f4","type":"TEXTAREA","key":"reason","label":"报销事由","required":true,"validator":{"min":5,"max":500},"fullWidth":true},
  {"id":"f5","type":"TEXTAREA","key":"remark","label":"备注","validator":{"max":200},"fullWidth":true}
]$json$,
       1,
       'system'
 WHERE NOT EXISTS (SELECT 1 FROM sys_form_design WHERE code = 'expense-apply');