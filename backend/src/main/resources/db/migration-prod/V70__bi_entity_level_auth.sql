-- BI 大屏 / 报表的实体级授权 seed
--
-- 背景：此前大屏与报表只有模块级权限（bi:dashboard:view / report:view …），
-- 拿到模块权就能看到模块里所有报表。本迁移引入实体级权限码
--   bi:dashboard:{code}:{verb}   —— 大屏（sys_bi_dashboard.code）
--   report:{code}:{verb}         —— 报表（sys_report.code）
-- verb ∈ {view, design, publish, delete}，与档案库 documents:{slug}:{verb} 同构。
--
-- 本脚本做三件事：
--   1. 给存量每张报表 / 每张大屏注册 4 个权限码
--   2. 回填角色绑定，避免上线瞬间所有人失去访问
--   3. 把已发布菜单的 permission 从模块级改写成实体级 view
--
-- 第 2 点为什么不是「只给 admin」：
-- 档案库方案（V23）当时只有 admin 一个在用，给 admin 就够了。但大屏 / 报表
-- 上线前走的是模块级权限，operator / viewer 等角色可能正持有 bi:dashboard:view。
-- 只 seed admin 会让这些角色在升级瞬间看不到任何大屏 —— 那是与「引入实体级授权」
-- 毫无关系的功能倒退。所以这里按「当前持有对应模块级权限的角色」原样回填，
-- 升级后行为完全不变，之后再由管理员逐张收紧。
--
-- 幂等：权限码用 NOT EXISTS 去重，角色绑定用 NOT EXISTS 去重，重复执行无副作用。

-- ===================== 1. 大屏权限码 =====================

INSERT INTO sys_permission (code, name, type)
SELECT 'bi:dashboard:' || d.code || ':view', d.name || ' 查看', 'API'
  FROM sys_bi_dashboard d
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code = 'bi:dashboard:' || d.code || ':view');

INSERT INTO sys_permission (code, name, type)
SELECT 'bi:dashboard:' || d.code || ':design', d.name || ' 编辑设计', 'API'
  FROM sys_bi_dashboard d
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code = 'bi:dashboard:' || d.code || ':design');

INSERT INTO sys_permission (code, name, type)
SELECT 'bi:dashboard:' || d.code || ':publish', d.name || ' 发布下线', 'API'
  FROM sys_bi_dashboard d
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code = 'bi:dashboard:' || d.code || ':publish');

INSERT INTO sys_permission (code, name, type)
SELECT 'bi:dashboard:' || d.code || ':delete', d.name || ' 删除', 'API'
  FROM sys_bi_dashboard d
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code = 'bi:dashboard:' || d.code || ':delete');

-- ===================== 2. 大屏角色回填 =====================
-- 模块级 bi:dashboard:{view|update|publish|delete} 的持有者
--   → 实体级同名 verb（update → design）
-- 注意：实体级权限码里的中间段是 code，模块级是固定字面量，
-- 所以这里靠「后缀」把两者对上，不能直接比 code。

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT rp.role_id, p.id
  FROM sys_permission p
  JOIN sys_permission mp
    ON mp.code = 'bi:dashboard:' || CASE
         WHEN p.code LIKE '%:view' THEN 'view'
         WHEN p.code LIKE '%:design' THEN 'update'
         WHEN p.code LIKE '%:publish' THEN 'publish'
         WHEN p.code LIKE '%:delete' THEN 'delete' END
  JOIN sys_role_perm rp ON rp.perm_id = mp.id
 WHERE (   p.code LIKE 'bi:dashboard:%:view'
        OR p.code LIKE 'bi:dashboard:%:design'
        OR p.code LIKE 'bi:dashboard:%:publish'
        OR p.code LIKE 'bi:dashboard:%:delete' )
   -- 排除模块级那 4 个码本身（它们的中间段就是 view/update/…，不带第二个冒号）
   AND p.code NOT IN ('bi:dashboard:view','bi:dashboard:update','bi:dashboard:publish','bi:dashboard:delete')
   AND NOT EXISTS (SELECT 1 FROM sys_role_perm x WHERE x.role_id = rp.role_id AND x.perm_id = p.id);

-- ===================== 3. 报表权限码 =====================

INSERT INTO sys_permission (code, name, type)
SELECT 'report:' || r.code || ':view', r.name || ' 查看', 'API'
  FROM sys_report r
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code = 'report:' || r.code || ':view');

INSERT INTO sys_permission (code, name, type)
SELECT 'report:' || r.code || ':design', r.name || ' 编辑设计', 'API'
  FROM sys_report r
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code = 'report:' || r.code || ':design');

INSERT INTO sys_permission (code, name, type)
SELECT 'report:' || r.code || ':publish', r.name || ' 发布下线', 'API'
  FROM sys_report r
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code = 'report:' || r.code || ':publish');

INSERT INTO sys_permission (code, name, type)
SELECT 'report:' || r.code || ':delete', r.name || ' 删除', 'API'
  FROM sys_report r
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code = 'report:' || r.code || ':delete');

-- ===================== 4. 报表角色回填 =====================

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT rp.role_id, p.id
  FROM sys_permission p
  JOIN sys_permission mp
    ON mp.code = 'report:' || CASE
         WHEN p.code LIKE '%:view' THEN 'view'
         WHEN p.code LIKE '%:design' THEN 'update'
         WHEN p.code LIKE '%:publish' THEN 'publish'
         WHEN p.code LIKE '%:delete' THEN 'delete' END
  JOIN sys_role_perm rp ON rp.perm_id = mp.id
 WHERE (   p.code LIKE 'report:%:view'
        OR p.code LIKE 'report:%:design'
        OR p.code LIKE 'report:%:publish'
        OR p.code LIKE 'report:%:delete' )
   -- 排除模块级 report:view / report:update / report:publish / report:delete 本身
   AND p.code NOT IN ('report:view','report:update','report:publish','report:delete','report:run')
   AND NOT EXISTS (SELECT 1 FROM sys_role_perm x WHERE x.role_id = rp.role_id AND x.perm_id = p.id);

-- ===================== 5. 已发布菜单 permission 改写 =====================
-- 发布出去的侧边栏入口挂的是模块级 permission，改成实体级 view 之后，
-- 入口才会跟着实体授权走 —— 收回某张报表的 view，入口对没权限的角色自然消失。
--
-- 路径里的数字是**自增 id**，而权限码中间段是 **code**，必须 join 一次拿 code，
-- 不能拿 id 直接拼（那样拼出来的权限码永远匹配不上任何已注册的码）。

UPDATE sys_menu m
   SET permission = 'bi:dashboard:' || d.code || ':view'
  FROM sys_bi_dashboard d
 WHERE m.path ~ '^/bi/dashboard/view/[0-9]+$'
   AND substring(m.path from '/view/([0-9]+)$')::bigint = d.id
   AND m.permission IS DISTINCT FROM 'bi:dashboard:' || d.code || ':view';

UPDATE sys_menu m
   SET permission = 'report:' || r.code || ':view'
  FROM sys_report r
 WHERE m.path ~ '^/bi/report/view/[0-9]+$'
   AND substring(m.path from '/view/([0-9]+)$')::bigint = r.id
   AND m.permission IS DISTINCT FROM 'report:' || r.code || ':view';