-- V27: 任务清单 (Todo) 顶级模块 —— 3 张表 + 5 个权限码 + 顶级菜单 + 角色绑定
-- 数据归属：每用户独立（owner_id = sys_user.id），admin 不持 view-all perm。
-- 任务管理子菜单下仅 1 页（task/Index）；本期不做"分类/标签中心"独立页，
-- 标签管理 inline 在任务弹窗内通过 /api/todo/tags 维护。

-- ---------------------------------------------------------------------------
-- 1. 任务主表 sys_todo_task
-- ---------------------------------------------------------------------------
CREATE TABLE sys_todo_task (
  id           INTEGER      PRIMARY KEY AUTOINCREMENT,
  owner_id     INTEGER      NOT NULL,
  title        VARCHAR(200) NOT NULL,
  description  TEXT,
  -- HIGH / MEDIUM / LOW / NONE
  priority     VARCHAR(16)  NOT NULL DEFAULT 'NONE',
  -- PENDING / COMPLETED
  status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
  due_at       TIMESTAMP,
  parent_id    INTEGER,
  -- NONE / DAILY / WEEKLY / MONTHLY
  recurrence   VARCHAR(16)  NOT NULL DEFAULT 'NONE',
  completed_at TIMESTAMP,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (owner_id)  REFERENCES sys_user(id)        ON DELETE CASCADE,
  FOREIGN KEY (parent_id) REFERENCES sys_todo_task(id)   ON DELETE SET NULL
);
CREATE INDEX idx_todo_task_owner  ON sys_todo_task(owner_id);
CREATE INDEX idx_todo_task_status ON sys_todo_task(status);
CREATE INDEX idx_todo_task_due_at ON sys_todo_task(due_at);

-- ---------------------------------------------------------------------------
-- 2. 标签表 sys_todo_tag —— 按 owner 隔离；同一 owner 内 name 唯一
-- ---------------------------------------------------------------------------
CREATE TABLE sys_todo_tag (
  id         INTEGER      PRIMARY KEY AUTOINCREMENT,
  owner_id   INTEGER      NOT NULL,
  name       VARCHAR(64)  NOT NULL,
  created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (owner_id) REFERENCES sys_user(id) ON DELETE CASCADE,
  UNIQUE (owner_id, name)
);

-- ---------------------------------------------------------------------------
-- 3. 任务-标签 关联表 sys_todo_task_tag —— 双 cascade，
--    删任务 / 删标签都会自动清理中间表；后续 orphan tag 由 service 兜底清理。
-- ---------------------------------------------------------------------------
CREATE TABLE sys_todo_task_tag (
  task_id INTEGER NOT NULL,
  tag_id  INTEGER NOT NULL,
  PRIMARY KEY (task_id, tag_id),
  FOREIGN KEY (task_id) REFERENCES sys_todo_task(id) ON DELETE CASCADE,
  FOREIGN KEY (tag_id)  REFERENCES sys_todo_tag(id)  ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- 4. 5 个权限码
-- ---------------------------------------------------------------------------
INSERT INTO sys_permission (code, name, type)
SELECT 'todo:task:view',   'Todo task view',   'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'todo:task:view');

INSERT INTO sys_permission (code, name, type)
SELECT 'todo:task:create', 'Todo task create', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'todo:task:create');

INSERT INTO sys_permission (code, name, type)
SELECT 'todo:task:update', 'Todo task update', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'todo:task:update');

INSERT INTO sys_permission (code, name, type)
SELECT 'todo:task:delete', 'Todo task delete', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'todo:task:delete');

INSERT INTO sys_permission (code, name, type)
SELECT 'todo:task:view-all', 'Todo task view all users', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'todo:task:view-all');

-- ---------------------------------------------------------------------------
-- 5. 角色绑定：admin / operator / viewer 全部基础 4 perm（每用户管自己的任务）；
--    admin 额外持 view-all（本期 UI 不暴露，留作后续运营视角扩展）。
-- ---------------------------------------------------------------------------
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code IN ('admin', 'operator', 'viewer')
   AND p.code IN ('todo:task:view', 'todo:task:create', 'todo:task:update', 'todo:task:delete')
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'admin'
   AND p.code = 'todo:task:view-all'
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

-- ---------------------------------------------------------------------------
-- 6. 顶级菜单 DIR：任务清单（id=60, sort=6 —— 介于 Documents=5 与 System=9 之间）
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type, status)
SELECT 60, 0, 'Task List', '/todo', NULL, 'List', 6, 'todo:task:view', 'DIR', 1
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 60);

-- ---------------------------------------------------------------------------
-- 7. 子菜单 MENU：任务管理（id=61）
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type, status)
SELECT 61, 60, 'Task Management', '/todo', 'todo/task/Index', 'Memo', 1, 'todo:task:view', 'MENU', 1
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 61);
