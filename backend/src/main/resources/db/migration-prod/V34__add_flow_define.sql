-- V34: 流程设计器（Flow Designer）—— Phase 3
-- sys_flow_define   —— 流程定义（一份 FlowJson + 角色 + 默认表单）
-- sys_flow_instance —— 流程实例（Phase 4 运行时填实，本期仅建表）
-- sys_flow_task     —— 审批任务（Phase 4 填实）
-- sys_flow_history  —— 审批历史（Phase 4 填实）
--
-- FlowJson 结构（与 mfish 100% 兼容）：
--   {
--     "nodes": [
--       { "id": "n_start", "data": { "type": "START", "label": "开始" } },
--       { "id": "n1", "data": { "type": "APPROVAL", "label": "直属主管",
--         "userIds": ["u_001"], "roleIds": [], "orgIds": [],
--         "approvalType": "OR", "formCode": "leave_request" } },
--       { "id": "n_excl", "data": { "type": "CONDITION", "label": "条件",
--         "branches": [ { "label": "是", "condition": "${days > 3}" } ] } },
--       { "id": "n_end", "data": { "type": "END", "label": "结束" } }
--     ],
--     "edges": [
--       { "id": "e1", "source": "n_start", "target": "n1", "data": { "condition": "" } },
--       { "id": "e2", "source": "n1", "target": "n_end", "data": { "condition": "approved" } }
--     ]
--   }
--
-- 节点类型见 design.flow.enums.NodeType
-- approvalType 见 design.flow.enums.ApproveType
-- 条件表达式：Spring ExpressionParser 解析 SpEL（Phase 4 引入）

-- ---------------------------------------------------------------------------
-- 1. sys_flow_define
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_flow_define (
  id                BIGSERIAL       PRIMARY KEY,
  code              VARCHAR(64)   NOT NULL UNIQUE,           -- 业务码（kebab-case），如 'leave_request'
  name              VARCHAR(128)  NOT NULL,                  -- 显示名
  description       VARCHAR(512),
  flow_json         TEXT          NOT NULL DEFAULT '{"nodes":[],"edges":[]}',
  default_form_code VARCHAR(64),                            -- 默认表单 code（可选）
  status            SMALLINT      NOT NULL DEFAULT 0,        -- 0=草稿 / 1=已发布
  version           INTEGER       NOT NULL DEFAULT 1,
  created_by        VARCHAR(64),
  created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_flow_define_status ON sys_flow_define(status);
CREATE INDEX IF NOT EXISTS idx_flow_define_code   ON sys_flow_define(code);

-- ---------------------------------------------------------------------------
-- 2. sys_flow_instance —— 流程实例（Phase 4 运行时填实，本期先建表）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_flow_instance (
  id              BIGSERIAL       PRIMARY KEY,
  flow_id         BIGINT       NOT NULL,
  flow_code       VARCHAR(64)   NOT NULL,
  business_key    VARCHAR(128)  NOT NULL,                   -- 业务表主键
  state           VARCHAR(32)   NOT NULL,                   -- RUNNING / APPROVED / REJECTED / CANCELED
  current_node_id VARCHAR(64),
  variables_json  TEXT,                                     -- 流程变量（form data + 系统变量）
  started_by      VARCHAR(64),
  started_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at     TIMESTAMP,
  FOREIGN KEY (flow_id) REFERENCES sys_flow_define(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_flow_instance_state        ON sys_flow_instance(state);
CREATE INDEX IF NOT EXISTS idx_flow_instance_business_key ON sys_flow_instance(business_key);
CREATE INDEX IF NOT EXISTS idx_flow_instance_flow_code    ON sys_flow_instance(flow_code);

-- ---------------------------------------------------------------------------
-- 3. sys_flow_task —— 审批任务（Phase 4 填实）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_flow_task (
  id             BIGSERIAL       PRIMARY KEY,
  instance_id    BIGINT       NOT NULL,
  flow_id        BIGINT       NOT NULL,
  node_id        VARCHAR(64)   NOT NULL,
  node_label     VARCHAR(128),
  assignee_type  VARCHAR(16)   NOT NULL,                    -- USER / ROLE / ORG
  assignee_id    VARCHAR(64)   NOT NULL,
  assignee_name  VARCHAR(128),
  status         VARCHAR(16)   NOT NULL DEFAULT 'PENDING',  -- PENDING / APPROVED / REJECTED / SKIPPED
  comment        VARCHAR(512),
  todo_task_id   BIGINT,                                   -- 联动 todo 模块（Phase 4）
  created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at   TIMESTAMP,
  FOREIGN KEY (instance_id) REFERENCES sys_flow_instance(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_flow_task_assignee  ON sys_flow_task(assignee_type, assignee_id, status);
CREATE INDEX IF NOT EXISTS idx_flow_task_instance  ON sys_flow_task(instance_id);
CREATE INDEX IF NOT EXISTS idx_flow_task_todo      ON sys_flow_task(todo_task_id);

-- ---------------------------------------------------------------------------
-- 4. sys_flow_history —— 审批历史
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_flow_history (
  id             BIGSERIAL       PRIMARY KEY,
  instance_id    BIGINT       NOT NULL,
  node_id        VARCHAR(64)   NOT NULL,
  node_label     VARCHAR(128),
  action         VARCHAR(16)   NOT NULL,                    -- SUBMIT / APPROVE / REJECT / DELEGATE / JUMP
  operator       VARCHAR(64)   NOT NULL,
  operator_name  VARCHAR(128),
  comment        VARCHAR(512),
  created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (instance_id) REFERENCES sys_flow_instance(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_flow_history_instance ON sys_flow_history(instance_id);

-- ---------------------------------------------------------------------------
-- 5. 5 个权限码（流程定义）
--     INSTALL/UNINSTALL 在 Phase 4 引入；本期先 view/create/update/delete
-- ---------------------------------------------------------------------------
INSERT INTO sys_permission (code, name, type)
SELECT 'design:flow:view',    'Flow designer view',    'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'design:flow:view');

INSERT INTO sys_permission (code, name, type)
SELECT 'design:flow:create',  'Flow designer create',  'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'design:flow:create');

INSERT INTO sys_permission (code, name, type)
SELECT 'design:flow:update',  'Flow designer update',  'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'design:flow:update');

INSERT INTO sys_permission (code, name, type)
SELECT 'design:flow:delete',  'Flow designer delete',  'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'design:flow:delete');

INSERT INTO sys_permission (code, name, type)
SELECT 'design:flow:deploy',  'Flow designer deploy',  'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'design:flow:deploy');

-- ---------------------------------------------------------------------------
-- 6. 角色绑定：admin 全 5 perm；operator / viewer 仅 view
--    V24 已给 admin 全 design:flow:* 的 view/update/delete/enable，本步骤只补 create/deploy
--    并保证 operator/viewer 也有 view perm
-- ---------------------------------------------------------------------------
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'admin'
   AND p.code IN ('design:flow:create', 'design:flow:deploy')
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code IN ('operator', 'viewer')
   AND p.code = 'design:flow:view'
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );