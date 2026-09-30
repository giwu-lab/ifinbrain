-- V28: 任务清单升级 — assignee + 附件
-- 1. sys_todo_task.owner_id → assignee_id (重命名) + 新增 created_by 字段
-- 2. 新表 sys_todo_attachment
-- 3. 4 个新权限码 + 角色绑定

-- ---------------------------------------------------------------------------
-- 1. sys_todo_task schema 升级
-- ---------------------------------------------------------------------------
ALTER TABLE sys_todo_task RENAME COLUMN owner_id TO assignee_id;
DROP INDEX IF EXISTS idx_todo_task_owner;
CREATE INDEX idx_todo_task_assignee   ON sys_todo_task(assignee_id);

-- created_by = 任务创建者（可能与 assignee 不同：admin 创建任务给其他人时分离）
-- 用 SET NULL 而非 CASCADE：admin 被删后任务仍可保留，由 admin 视图查看
ALTER TABLE sys_todo_task ADD COLUMN created_by INTEGER REFERENCES sys_user(id) ON DELETE SET NULL;
CREATE INDEX idx_todo_task_created_by ON sys_todo_task(created_by);

-- ---------------------------------------------------------------------------
-- 2. sys_todo_attachment
-- ---------------------------------------------------------------------------
CREATE TABLE sys_todo_attachment (
  id                INTEGER       PRIMARY KEY AUTOINCREMENT,
  task_id           INTEGER       NOT NULL,
  storage_key       VARCHAR(512)  NOT NULL UNIQUE,
  storage_type      VARCHAR(16)   NOT NULL,
  original_filename VARCHAR(255)  NOT NULL,
  size_bytes        BIGINT        NOT NULL,
  sha256            CHAR(64),
  mime_type         VARCHAR(128),
  uploader_id       INTEGER       REFERENCES sys_user(id) ON DELETE SET NULL,
  uploader_name     VARCHAR(64),
  status            SMALLINT      NOT NULL DEFAULT 1,
  created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (task_id) REFERENCES sys_todo_task(id) ON DELETE CASCADE
);
CREATE INDEX idx_todo_attachment_task ON sys_todo_attachment(task_id);

-- ---------------------------------------------------------------------------
-- 3. 4 个新权限码
-- ---------------------------------------------------------------------------
INSERT INTO sys_permission (code, name, type)
SELECT 'todo:attachment:upload',   'Todo attachment upload',   'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'todo:attachment:upload');

INSERT INTO sys_permission (code, name, type)
SELECT 'todo:attachment:download', 'Todo attachment download', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'todo:attachment:download');

INSERT INTO sys_permission (code, name, type)
SELECT 'todo:attachment:delete',   'Todo attachment delete',   'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'todo:attachment:delete');

INSERT INTO sys_permission (code, name, type)
SELECT 'todo:task:assign',         'Todo task reassign',       'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'todo:task:assign');

-- ---------------------------------------------------------------------------
-- 4. 角色绑定
--    - 3 个附件 perm：admin / operator / viewer 全开
--    - todo:task:assign：仅 admin（普通用户改自己任务的执行人走 created_by 服务层特判，
--      不依赖此 perm，但保留此 perm 给 admin 用）
-- ---------------------------------------------------------------------------
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code IN ('admin', 'operator', 'viewer')
   AND p.code IN ('todo:attachment:upload', 'todo:attachment:download', 'todo:attachment:delete')
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'admin'
   AND p.code = 'todo:task:assign'
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );