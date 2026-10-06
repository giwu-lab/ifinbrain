-- V17: 档案管理 (Document Management) — 建表 + 4 权限码 + 顶级菜单 + 角色绑定。
-- 子菜单与种子类目由 admin 在 UI 运行时创建（P2 阶段实现 DocumentCategoryService）。

-- ---------------------------------------------------------------------------
-- 1. 类目表
-- ---------------------------------------------------------------------------
CREATE TABLE sys_document_category (
  id           BIGSERIAL      PRIMARY KEY,
  name         VARCHAR(64)  NOT NULL,
  slug         VARCHAR(64)  NOT NULL UNIQUE,
  icon         VARCHAR(64)  NOT NULL DEFAULT 'Folder',
  sort         INTEGER      NOT NULL DEFAULT 0,
  status       SMALLINT     NOT NULL DEFAULT 1,
  remark       VARCHAR(255),
  created_by   BIGINT,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_doc_cat_status ON sys_document_category(status, sort);

-- ---------------------------------------------------------------------------
-- 2. 档案表
-- ---------------------------------------------------------------------------
CREATE TABLE sys_document (
  id                BIGSERIAL      PRIMARY KEY,
  category_id       BIGINT      NOT NULL,
  name              VARCHAR(255) NOT NULL,
  storage_key       VARCHAR(512) NOT NULL,
  storage_type      VARCHAR(16)  NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  size_bytes        BIGINT       NOT NULL,
  sha256            CHAR(64),
  mime_type         VARCHAR(128),
  uploader_id       BIGINT,
  uploader_name     VARCHAR(64),
  remark            VARCHAR(255),
  status            SMALLINT     NOT NULL DEFAULT 1,
  created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (category_id) REFERENCES sys_document_category(id)
);
CREATE INDEX idx_doc_category ON sys_document(category_id, created_at DESC);
CREATE INDEX idx_doc_uploader ON sys_document(uploader_id);

-- ---------------------------------------------------------------------------
-- 3. 4 个全局权限码
-- ---------------------------------------------------------------------------
INSERT INTO sys_permission (code, name, type) VALUES
  ('documents:view',   'Document view',   'API'),
  ('documents:upload', 'Document upload', 'API'),
  ('documents:update', 'Document update', 'API'),
  ('documents:delete', 'Document delete', 'API');

-- ---------------------------------------------------------------------------
-- 4. 顶级菜单：档案管理（DIR；sort=5，置于 数据管理=4 与 系统设置=9 之间）
--    子菜单由 admin 在 UI 创建类目时同步插入 sys_menu（P2 阶段）。
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type, status) VALUES
  (50, 0, 'Documents', '/documents', NULL, 'Folder', 5, 'documents:view', 'DIR', 1);

-- ---------------------------------------------------------------------------
-- 5. 角色绑定：admin / operator 全 4 个；viewer 仅 view
-- ---------------------------------------------------------------------------
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code IN ('admin', 'operator')
   AND p.code IN ('documents:view', 'documents:upload', 'documents:update', 'documents:delete');

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'viewer'
   AND p.code = 'documents:view';
