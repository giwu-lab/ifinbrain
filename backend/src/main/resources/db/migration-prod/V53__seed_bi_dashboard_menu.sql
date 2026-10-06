-- V53: BI 大屏模块的权限码 + 角色绑定 + 菜单
--
-- 权限码（AGENTS §3 格式 <module>:<resource>:<verb>）：
--   bi:dashboard:view      大屏查看 / 播放
--   bi:dashboard:create    新建 / 复制
--   bi:dashboard:update    编辑（含 layout 保存）
--   bi:dashboard:delete    删除
--   bi:dashboard:publish   发布 / 取消发布
--   bi:dataset:view        大屏取数（播放大屏实际需要的后端权限）
--
-- 角色分配：
--   admin    全部 6 个
--   operator view / create / update / publish / dataset:view（刻意不给 delete）
--   viewer   view / dataset:view（只播放，不碰管理）
--
-- 为什么不复用 data:query:run：那是「数据管理 → 查询」模块的权限位，viewer 没有。
-- BiDataService 走自己的 loadForRun（无鉴权版本）+ 入口 bi:dataset:view，两条链路互不干扰。
--
-- 菜单：顶级 DIR sort=7（现有 Design=3 / Data=4 / Documents=5 / Todo=6 / System=9）。
-- sys_menu.name 存英文（V10/V17/V24/V27/V50 一致），它是 i18n 未命中时的兜底显示名 ——
-- 中文文案由前端 menu.* 命名空间提供。
--
-- 幂等：
--   · 权限码 / 角色绑定都用 NOT EXISTS 守卫
--   · 菜单不写死 id（BIGSERIAL 自增），用 path + type 判存在 —— 避免与后续迁移的
--     预留 id 段撞车（V50 就用过 27 这个"当时空着"的号）。

-- ---------------------------------------------------------------------------
-- 1. 权限码
-- ---------------------------------------------------------------------------
INSERT INTO sys_permission (code, name, type)
SELECT 'bi:dashboard:view', 'BI dashboard view', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'bi:dashboard:view');

INSERT INTO sys_permission (code, name, type)
SELECT 'bi:dashboard:create', 'BI dashboard create', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'bi:dashboard:create');

INSERT INTO sys_permission (code, name, type)
SELECT 'bi:dashboard:update', 'BI dashboard update', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'bi:dashboard:update');

INSERT INTO sys_permission (code, name, type)
SELECT 'bi:dashboard:delete', 'BI dashboard delete', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'bi:dashboard:delete');

INSERT INTO sys_permission (code, name, type)
SELECT 'bi:dashboard:publish', 'BI dashboard publish', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'bi:dashboard:publish');

INSERT INTO sys_permission (code, name, type)
SELECT 'bi:dataset:view', 'BI dataset view', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'bi:dataset:view');

-- ---------------------------------------------------------------------------
-- 2. 角色绑定
-- ---------------------------------------------------------------------------
-- admin：全部
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'admin'
   AND p.code IN ('bi:dashboard:view', 'bi:dashboard:create', 'bi:dashboard:update',
                  'bi:dashboard:delete', 'bi:dashboard:publish', 'bi:dataset:view')
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

-- operator：可建可编可发布，不能删
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'operator'
   AND p.code IN ('bi:dashboard:view', 'bi:dashboard:create', 'bi:dashboard:update',
                  'bi:dashboard:publish', 'bi:dataset:view')
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

-- viewer：只看 / 只播
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code = 'viewer'
   AND p.code IN ('bi:dashboard:view', 'bi:dataset:view')
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

-- ---------------------------------------------------------------------------
-- 3. 顶级目录 DIR：BI 可视化分析（path=/bi, sort=7）
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (parent_id, name, path, component, icon, sort, permission, type, status)
SELECT 0, 'BI Analytics', '/bi', NULL, 'DataBoard', 7, 'bi:dashboard:view', 'DIR', 1
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/bi' AND type = 'DIR');

-- ---------------------------------------------------------------------------
-- 4. 子菜单：只保留「大屏管理」（列表页）与「展示墙」（无参数的独立页面）
-- ---------------------------------------------------------------------------
-- 注意（V54 修正）：这里**不能**给设计器 / 播放页建带 :id 占位符的菜单行。
-- sys_menu.path 是真实 URL 而非路由模板，写成 '/bi/dashboard/design/:id' 时，
-- 点侧边栏会真的导航到字面量路径 → Number(':id') = NaN → /api/bi/dashboard/NaN → 500。
-- 设计器与播放页是「操作入口」，从列表页的编辑 / 预览按钮进入；
-- 对应路由保留在 staticRoutes（router/index.ts），供按钮与深链使用。
INSERT INTO sys_menu (parent_id, name, path, component, icon, sort, permission, type, status)
SELECT d.id, 'Dashboard Manager', '/bi/dashboard', 'bi/dashboard/Index', 'Odometer', 1,
       'bi:dashboard:view', 'MENU', 1
  FROM sys_menu d
 WHERE d.path = '/bi' AND d.type = 'DIR'
   AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.path = '/bi/dashboard');

-- 展示墙：轮播全部已发布大屏。路径无 :id，与播放页同属 tab 白名单，不占 tab。
INSERT INTO sys_menu (parent_id, name, path, component, icon, sort, permission, type, status)
SELECT d.id, 'Video Wall', '/bi/dashboard/wall', 'bi/dashboard/Wall', 'Film', 2,
       'bi:dashboard:view', 'MENU', 1
  FROM sys_menu d
 WHERE d.path = '/bi' AND d.type = 'DIR'
   AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.path = '/bi/dashboard/wall');
