-- V30: 表单设计器（Form Designer）—— 独立模块
-- sys_form_design —— 表单元数据（一行 = 一份表单 schema）
--   fields_json TEXT：JSON 数组，描述字段列表
--   与流程设计（V31+）解耦：表单可单独使用，也可被流程节点挂载（ApprovalNode.data.formCode）。
--
-- 数据约定：fields_json 结构
--   {
--     "version": 1,
--     "fields": [
--       { "id": "f1", "type": "text", "key": "name", "label": "姓名",
--         "required": true, "defaultValue": "", "placeholder": "...",
--         "options": [{"label":"A","value":"a"}],
--         "validator": {"min": 1, "max": 50, "pattern": ".*"} }
--     ]
--   }
-- 字段类型见 design.form.enums.FieldType（前端 13 种、后端白名单同步）。

-- ---------------------------------------------------------------------------
-- 1. sys_form_design（IF NOT EXISTS 让迁移幂等：DB 可能在 V30 阶段已建表）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_form_design (
  id           INTEGER       PRIMARY KEY AUTOINCREMENT,
  code         VARCHAR(64)   NOT NULL UNIQUE,             -- 业务码（kebab-case），如 'leave_request'
  name         VARCHAR(128)  NOT NULL,                    -- 显示名
  description  VARCHAR(512),
  fields_json  TEXT          NOT NULL DEFAULT '{"version":1,"fields":[]}',
  status       SMALLINT      NOT NULL DEFAULT 0,          -- 0=草稿 / 1=已发布
  created_by   VARCHAR(64),
  created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_form_design_status ON sys_form_design(status);
CREATE INDEX IF NOT EXISTS idx_form_design_code   ON sys_form_design(code);

-- ---------------------------------------------------------------------------
-- 2. 5 个权限码
-- ---------------------------------------------------------------------------
INSERT INTO sys_permission (code, name, type)
SELECT 'design:form:view',    'Form designer view',    'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'design:form:view');

INSERT INTO sys_permission (code, name, type)
SELECT 'design:form:create',  'Form designer create',  'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'design:form:create');

INSERT INTO sys_permission (code, name, type)
SELECT 'design:form:update',  'Form designer update',  'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'design:form:update');

INSERT INTO sys_permission (code, name, type)
SELECT 'design:form:delete',  'Form designer delete',  'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'design:form:delete');

INSERT INTO sys_permission (code, name, type)
SELECT 'design:form:publish', 'Form designer publish', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'design:form:publish');

-- ---------------------------------------------------------------------------
-- 3. 角色绑定：admin 全 5 perm；operator / viewer 仅 view
-- ---------------------------------------------------------------------------
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'admin'
   AND p.code LIKE 'design:form:%'
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code IN ('operator', 'viewer')
   AND p.code = 'design:form:view'
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

-- ---------------------------------------------------------------------------
-- 4. 子菜单 MENU：表单设计（id=33，置于设计 DIR 下，sort=1 位于流程设计之前）
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type, status)
SELECT 33, 30, 'Form Designer', '/design/form', 'design/form/Index', 'Document', 1, 'design:form:view', 'MENU', 1
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 33);

-- 流程设计菜单 id=32 的 sort 改为 2，让表单设计排第一
UPDATE sys_menu SET sort = 2 WHERE id = 32 AND sort = 2;
-- （id=32 在 V24 已建为 sort=2，本步骤保留语义；实际无需变更，仅文档化）