-- V50: 组织架构（部门）模块
--
-- 背景：流程审批节点（ApprovalNodeConfig.vue:124）和表单 DEPT 字段（DeptField.vue:28）
-- 早就在调 GET /api/system/depts/tree，但后端从未建过 dept 表、也没有任何 Controller ——
-- 那两个调用一直在吃 404（并被 GlobalExceptionHandler 兜底成 500「服务器内部错误」）。
-- 本次补齐数据层 + 接口 + 权限 + 菜单入口。
--
-- 表设计说明：
--   parent_id = 0 表示根部门（不用 NULL，与 sys_menu 的约定保持一致）。
--   code 可空；非空时全局唯一，留空则不参与唯一性约束（部分小部门不需要编码）。
--   status 0=停用 / 1=启用。停用不级联子部门，由使用方（审批人选取等）自行过滤。

CREATE TABLE IF NOT EXISTS sys_dept (
  id          BIGSERIAL     PRIMARY KEY,
  parent_id   BIGINT        NOT NULL DEFAULT 0,
  name        VARCHAR(128)  NOT NULL,
  code        VARCHAR(64),
  leader      VARCHAR(64),
  phone       VARCHAR(32),
  sort        INTEGER       NOT NULL DEFAULT 0,
  status      SMALLINT      NOT NULL DEFAULT 1,
  remark      VARCHAR(512),
  created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sys_dept_parent ON sys_dept(parent_id);
CREATE INDEX IF NOT EXISTS idx_sys_dept_sort   ON sys_dept(sort);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sys_dept_code ON sys_dept(code) WHERE code IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 权限码（命名沿用 system 模块的 xxx:list 惯例）
-- ---------------------------------------------------------------------------
INSERT INTO sys_permission (code, name, type)
SELECT 'system:dept:list', 'Department list', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'system:dept:list');

INSERT INTO sys_permission (code, name, type)
SELECT 'system:dept:create', 'Department create', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'system:dept:create');

INSERT INTO sys_permission (code, name, type)
SELECT 'system:dept:update', 'Department update', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'system:dept:update');

INSERT INTO sys_permission (code, name, type)
SELECT 'system:dept:delete', 'Department delete', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'system:dept:delete');

-- ---------------------------------------------------------------------------
-- 角色绑定：admin 全 4 项；viewer 只读。
-- 刻意不给 task_manager —— 它是「只管任务」的跨用户角色，不涉及组织架构。
-- ---------------------------------------------------------------------------
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'admin'
   AND p.code LIKE 'system:dept:%'
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'viewer'
   AND p.code = 'system:dept:list'
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

-- ---------------------------------------------------------------------------
-- 菜单：id=27（20~29 号段原本就空着 27/28/29），挂在「系统设置」DIR(20) 下，sort=5
-- 排在「存储设置」(4) 与「字典管理」(6) 之间。
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type, status)
SELECT 27, 20, 'Organization', '/system/dept', 'system/dept/Index', 'OfficeBuilding', 5, 'system:dept:list', 'MENU', 1
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 27);

-- ---------------------------------------------------------------------------
-- 初始数据：与档案库已有的两个部门档案（菜单 170 财务部 / 172 技术部）对齐
-- ---------------------------------------------------------------------------
INSERT INTO sys_dept (parent_id, name, code, leader, sort, status, remark)
SELECT 0, '总公司', 'HQ', NULL, 0, 1, '组织架构根节点'
 WHERE NOT EXISTS (SELECT 1 FROM sys_dept WHERE code = 'HQ');

INSERT INTO sys_dept (parent_id, name, code, leader, sort, status, remark)
SELECT d.id, '财务部', 'FIN', NULL, 1, 1, '发票与费用相关档案'
  FROM sys_dept d
 WHERE d.code = 'HQ'
   AND NOT EXISTS (SELECT 1 FROM sys_dept WHERE code = 'FIN');

INSERT INTO sys_dept (parent_id, name, code, leader, sort, status, remark)
SELECT d.id, '技术部', 'TECH', NULL, 2, 1, '研发与运维相关档案'
  FROM sys_dept d
 WHERE d.code = 'HQ'
   AND NOT EXISTS (SELECT 1 FROM sys_dept WHERE code = 'TECH');
