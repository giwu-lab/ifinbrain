-- V62: 笔记管理模块（档案管理 → 笔记管理）
--
-- 背景
--   档案管理模块（/documents）目前只有「档案库」+「库内文件」两类资产：
--   文件走 sys_document + storage，权限是库级的 documents:{slug}:{view|upload|update|delete}。
--   但业务侧还缺一类东西 —— **围绕这些档案的说明 / 交接 / 结论**。这类内容的特点：
--     1) 要按「人 / 团队」授权（档案库那套库级权限表达不了「这篇只给财务部看」）
--     2) 要写 markdown（文件存储只能当附件，不能当可编辑正文）
--     3) 要版本历史（多人轮流改，不能互相覆盖）
--   所以笔记是独立的一等实体，不复用 sys_document。
--
-- 本次新增
--   页面：/documents/notes（笔记管理），Markdown 双栏编辑 + 共享 + 团队笔记
--   实体：sys_note / sys_note_share / sys_note_revision
--
-- 权限码（AGENTS §3 格式 <module>:<resource>:<verb>）：
--   note:view         进入模块 + 读（可见性判定之后）
--   note:create       新建
--   note:update       编辑自己 / 被授权编辑的
--   note:delete       删除**自己**的
--   note:delete-any   删除**任何人**的（审计兜底，只给 admin）
--   note:share        改可见性 / 维护共享名单
--   note:manage-all   部门内管理他人笔记（对齐 todo:task:manage-all 的粒度）
--
-- 为什么不复用 documents:{slug}:*：
--   库级权限能表达「谁能看到这个库」，表达不了「这篇笔记只给某几个人」。
--   两者是正交维度：scope_type='LIBRARY' 的笔记**额外**吃 documents:{slug}:view/update，
--   其余 scope 走 note:* 体系。
--
-- 角色分配：
--   admin    全部 7 个
--   operator view/create/update/delete/share/manage-all（不给 delete-any）
--   viewer   仅 view（可读团队笔记，不可写）
--
-- 菜单：挂在顶级 DIR /documents 下，sort=-1（置顶；负数保证与现有 0/1 不撞，
--       也就没必要去改 /documents/archive 的 sort）。component 必须与
--       router/index.ts 的 builtInViews key 完全一致。
--
-- tags 列为什么是 TEXT 而不是 TEXT[]：
--   项目里没有任何 entity 映射过 PG 数组（SysMenu.config_json / SysSetting.value_text
--   都走 columnDefinition="TEXT"），首次引入数组映射一旦配错就是**启动期**炸，
--   风险收益不划算。改成经典的「首尾逗号包裹」规范化文本（",a,b,"），
--   过滤用 LIKE '%,tag,%'，精确匹配且不需要 GIN 索引。
--
-- 幂等：权限 / 角色绑定 / 菜单全部 NOT EXISTS 守卫；建表 IF NOT EXISTS；
--       外键与 CHECK 走 catalog 判存的 DO 块；重复执行无副作用。

-- ---------------------------------------------------------------------------
-- 1. 权限码
-- ---------------------------------------------------------------------------
INSERT INTO sys_permission (code, name, type)
SELECT 'note:view', 'Note view', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'note:view');

INSERT INTO sys_permission (code, name, type)
SELECT 'note:create', 'Note create', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'note:create');

INSERT INTO sys_permission (code, name, type)
SELECT 'note:update', 'Note update', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'note:update');

INSERT INTO sys_permission (code, name, type)
SELECT 'note:delete', 'Note delete own', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'note:delete');

INSERT INTO sys_permission (code, name, type)
SELECT 'note:delete-any', 'Note delete any (admin only)', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'note:delete-any');

INSERT INTO sys_permission (code, name, type)
SELECT 'note:share', 'Note share (visibility + member list)', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'note:share');

INSERT INTO sys_permission (code, name, type)
SELECT 'note:manage-all', 'Manage notes within own department', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'note:manage-all');

-- ---------------------------------------------------------------------------
-- 2. 角色绑定（按 code 查 role_id，不写死 1/2/3 —— V10 那种硬编码是历史包袱）
-- ---------------------------------------------------------------------------
-- admin：全部
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'admin'
   AND p.code IN ('note:view', 'note:create', 'note:update', 'note:delete',
                  'note:delete-any', 'note:share', 'note:manage-all')
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

-- operator：可写可共享可管同部门，但**不能删别人的**
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'operator'
   AND p.code IN ('note:view', 'note:create', 'note:update', 'note:delete',
                  'note:share', 'note:manage-all')
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

-- viewer：只读（能读团队 / 组织内共享的笔记，不能写）
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'viewer'
   AND p.code = 'note:view'
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

-- ---------------------------------------------------------------------------
-- 3. 子菜单：笔记管理（置顶）
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (parent_id, name, path, component, icon, sort, permission, type, status)
SELECT d.id, 'Notes', '/documents/notes', 'documents/notes/Index', 'Document', -1,
       'note:view', 'MENU', 1
  FROM sys_menu d
 WHERE d.path = '/documents' AND d.type = 'DIR'
   AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.path = '/documents/notes');

-- ---------------------------------------------------------------------------
-- 4. 笔记主表
-- ---------------------------------------------------------------------------
-- 为什么 title/content 之外还要冗余 owner_name / last_editor_name：
--   与 sys_document.uploader_name 同思路 —— 列表页每行都 join sys_user 是不必要的开销，
--   而且用户改名后历史笔记仍要读得出「当时是谁写的」。
--
-- version 是乐观锁：每次保存 +1，前端提交时带上打开时读到的值，不匹配即 409。
--   团队笔记里「互相覆盖」是唯一真正会丢内容的原因，必须挡在服务端。
CREATE TABLE IF NOT EXISTS sys_note (
    id                  BIGSERIAL     PRIMARY KEY,
    title               VARCHAR(255)  NOT NULL,
    content             TEXT          NOT NULL DEFAULT '',
    -- PERSONAL=仅自己 | TEAM=同部门 | LIBRARY=挂档案库 | ORG=组织内
    scope_type          VARCHAR(16)   NOT NULL DEFAULT 'PERSONAL',
    scope_dept_id       BIGINT,
    category_id         BIGINT,
    -- 首尾逗号包裹的标签串，如 ",财务,季度,"（见文件头说明）
    tags                TEXT          NOT NULL DEFAULT '',
    pinned              SMALLINT      NOT NULL DEFAULT 0,
    -- 1=ACTIVE 0=ARCHIVED（软删除未启用，「归档」是业务状态不是删除）
    status              SMALLINT      NOT NULL DEFAULT 1,
    related_document_id BIGINT,
    owner_id            BIGINT        NOT NULL,
    owner_name          VARCHAR(64),
    last_editor_id      BIGINT,
    last_editor_name    VARCHAR(64),
    version             INTEGER       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_note_owner  ON sys_note (owner_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_note_scope  ON sys_note (scope_type, scope_dept_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_note_lib    ON sys_note (category_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_note_status ON sys_note (status, pinned, updated_at DESC);

-- scope_type 与其必填外键的强绑定：TEAM 必须有部门，LIBRARY 必须有档案库。
-- 服务层漏判时这里是最后一道防线（PG 会在写入时直接拒绝）。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_note_scope') THEN
        ALTER TABLE sys_note ADD CONSTRAINT ck_note_scope CHECK (
            (scope_type = 'TEAM'    AND scope_dept_id IS NOT NULL) OR
            (scope_type = 'LIBRARY' AND category_id   IS NOT NULL) OR
            scope_type IN ('PERSONAL', 'ORG')
        );
    END IF;
    -- 档案库被删时，它的库级笔记一起走（对齐 V58 document_category_fk_cascade 的既有决定）
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_note_category') THEN
        ALTER TABLE sys_note ADD CONSTRAINT fk_note_category
            FOREIGN KEY (category_id) REFERENCES sys_document_category (id) ON DELETE CASCADE;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 5. 点名共享名单
-- ---------------------------------------------------------------------------
-- 「整组可见」（scope_type）和「点名授权」必须分开：前者是范围，后者是具体到人的读写授权。
CREATE TABLE IF NOT EXISTS sys_note_share (
    id          BIGSERIAL   PRIMARY KEY,
    note_id     BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    -- READ=只读 | WRITE=可编辑（团队协作里最常见的诉求）
    grant_type  VARCHAR(8)  NOT NULL DEFAULT 'READ',
    created_by  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_note_share UNIQUE (note_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_note_share_user ON sys_note_share (user_id);

-- user_id 故意**不建外键到 sys_user**：与 sys_note.owner_id 同样的取舍 ——
-- 人被删后笔记不该跟着消失（历史内容保留），列表侧用 LEFT JOIN 容忍悬空 id。

-- ---------------------------------------------------------------------------
-- 6. 版本历史
-- ---------------------------------------------------------------------------
-- 每次保存写一条**保存前**的快照；服务层保留最近 50 条/篇（长笔记无限膨胀会拖垮详情页）。
CREATE TABLE IF NOT EXISTS sys_note_revision (
    id          BIGSERIAL   PRIMARY KEY,
    note_id     BIGINT      NOT NULL,
    revision    INTEGER     NOT NULL,
    title       VARCHAR(255) NOT NULL,
    content     TEXT        NOT NULL,
    editor_id   BIGINT,
    editor_name VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_note_revision UNIQUE (note_id, revision)
);
CREATE INDEX IF NOT EXISTS idx_note_revision_note ON sys_note_revision (note_id, revision DESC);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_note_share_note') THEN
        ALTER TABLE sys_note_share ADD CONSTRAINT fk_note_share_note
            FOREIGN KEY (note_id) REFERENCES sys_note (id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_note_revision_note') THEN
        ALTER TABLE sys_note_revision ADD CONSTRAINT fk_note_revision_note
            FOREIGN KEY (note_id) REFERENCES sys_note (id) ON DELETE CASCADE;
    END IF;
END $$;