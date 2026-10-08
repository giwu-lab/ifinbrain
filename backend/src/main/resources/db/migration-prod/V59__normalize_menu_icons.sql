-- V59: 菜单图标归一化 —— 顶级入口按语义换成 Element Plus 规范 PascalCase 图标名
--
-- 背景（一个纯粹的渲染 bug，不是数据错）：
--   sys_menu.icon 存的是 Element Plus 图标名，而各 layout 原先按 `ElIcons[name]`
--   精确查表解析（现已收敛到 frontend/src/utils/icon.ts 的 resolveElIcon）。
--   Element Plus 的导出名全是 PascalCase（Odometer / Tickets / Setting），
--   但 V2/V6/V10 的种子数据写的是小写短横线（'odometer' / 'document' /
--   'magic-stick' / 'database' / 'setting'）→ 查不到 → 全部 fallback 成 Document。
--   表现就是侧边栏「仪表盘 / 发票管理 / 数据管理 / 系统设置」四个顶级入口
--   渲染出同一个通用文档图标，而后面几个后来加的 DIR（设计开发 MagicStick、
--   档案管理 Folder、任务清单 List、BI 报表 DataBoard）因为写成 PascalCase 反而正常 ——
--   同一栏里 8 个入口有 4 个图标是错的，肉眼就能看出不对齐。
--
-- 为什么两个方向都改：
--   1) 前端 resolveElIcon 已经能兼容各种写法（历史存量库 / admin 手填的值都能命中），
--      单靠它也能显示正确；但 DB 里仍留着小写值，「系统设置 → 菜单管理」列表页读原始
--      name/icon，展示和实际渲染对不上，后续再有人排查会很困惑。
--   2) 本迁移把 DB 值统一成规范 PascalCase，让 DB / 菜单管理页 / 侧边栏三处一致。
--   前端兜底保留 —— 兼容尚未跑迁移的旧库和 admin 后续手填的非规范写法。
--
-- 四个顶级入口的选型理由：
--   仪表盘 /dashboard    Odometer  仪表盘语义（沿用原 'odometer' 的本意，仅补大小写）
--   发票管理 /invoice     Tickets   「票」语义，比通用 Document 更贴发票管理；
--                                 且与子菜单 Upload / List / MagicStick 区分得开
--   数据管理 /data-mgmt   Grid      Element Plus **没有** Database 图标，原 'database'
--                                 属于臆造名，永远查不到；Grid（数据网格）是现有
--                                 图标里最贴数据管理的，且不与子菜单 Coin / Search 撞脸
--   系统设置 /system     Setting   沿用原 'setting' 本意，仅补大小写
--
-- 下面第二段把 12 个子菜单的历史小写值一并归一化，语义不变，只修大小写：
--   upload/list/magic-stick/coin/search/user/avatar/menu/folder/collection。
--   其中 /invoice/parser 的 'magic-stick' → 'MagicStick'。
--
-- 幂等：全部是 WHERE icon = <旧值> 形式的定向 UPDATE，
--       已迁移过的库重跑不会产生额外变更，也不依赖 admin 是否手改过（手改后不匹配即跳过）。

-- ---------------------------------------------------------------------------
-- 1. 顶级入口（本次语义调整的重点）
-- ---------------------------------------------------------------------------
UPDATE sys_menu SET icon = 'Odometer' WHERE id = 1   AND icon IN ('odometer', 'Odometer');
UPDATE sys_menu SET icon = 'Tickets'  WHERE id = 10  AND icon IN ('document', 'Document');
UPDATE sys_menu SET icon = 'Grid'     WHERE id = 40  AND icon IN ('database', 'Database', 'grid', 'Grid');
UPDATE sys_menu SET icon = 'Setting'  WHERE id = 20  AND icon IN ('setting', 'Setting');

-- ---------------------------------------------------------------------------
-- 2. 子菜单历史小写值归一化（语义不变，仅把名字对齐 Element Plus 导出名）
-- ---------------------------------------------------------------------------
UPDATE sys_menu SET icon = 'Upload'      WHERE id = 11 AND icon IN ('upload', 'Upload');
UPDATE sys_menu SET icon = 'List'        WHERE id = 12 AND icon IN ('list', 'List');
UPDATE sys_menu SET icon = 'MagicStick'  WHERE id = 25 AND icon IN ('magic-stick', 'magicStick', 'MagicStick');
UPDATE sys_menu SET icon = 'Coin'        WHERE id = 41 AND icon IN ('coin', 'Coin');
UPDATE sys_menu SET icon = 'Search'      WHERE id = 42 AND icon IN ('search', 'Search');
UPDATE sys_menu SET icon = 'User'        WHERE id = 21 AND icon IN ('user', 'User');
UPDATE sys_menu SET icon = 'Avatar'      WHERE id = 22 AND icon IN ('avatar', 'Avatar');
UPDATE sys_menu SET icon = 'Menu'        WHERE id = 23 AND icon IN ('menu', 'Menu');
UPDATE sys_menu SET icon = 'Folder'      WHERE id = 24 AND icon IN ('folder', 'Folder');
UPDATE sys_menu SET icon = 'Collection'  WHERE id = 26 AND icon IN ('collection', 'Collection');

-- ---------------------------------------------------------------------------
-- 3. 自检：列出仍然是非规范写法的菜单
--
--    判定口径 = 「精确查表会查不到」的等价特征：小写开头，或含 - _ . / 分隔符。
--    不用「比对 Element Plus 全量导出名」是因为那份名单会随 EP 版本漂移，
--    而这两种写法特征在 resolveElIcon 出现之前一定是解析失败的。
--    正常情况下这里应该报 0 条；有输出就说明有人手填了非规范值，去前端
--    resolveElIcon 的三级兜底上排查（现在不会显示错，但值得记一笔）。
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    leftovers text;
BEGIN
    SELECT string_agg(id || '(' || COALESCE(path, '-') || ')=' || icon, ', ' ORDER BY id)
      INTO leftovers
      FROM sys_menu
     WHERE icon IS NOT NULL
       AND btrim(icon) <> ''
       AND (icon ~ '^[a-z]' OR icon ~ '[-_./]');

    IF leftovers IS NULL THEN
        RAISE NOTICE 'V59: 全部菜单 icon 均为 Element Plus 规范 PascalCase 写法';
    ELSE
        RAISE NOTICE 'V59: 以下菜单 icon 仍为非规范写法（前端走 resolveElIcon 兜底，不会显示错）-> %', leftovers;
    END IF;
END $$;