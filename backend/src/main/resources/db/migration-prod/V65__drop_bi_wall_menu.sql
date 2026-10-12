-- 下线「展示墙」
--
-- 背景：展示墙（/bi/dashboard/wall）是 P5 阶段做的多屏轮播页。现在大屏自己
-- 就有独立的播放页（/bi/dashboard/view/{id}），侧边栏发布入口也指向播放页，
-- 展示墙多出来的那层「挑内容 / 排序 / 上下移」对单块屏幕没有意义，去掉。
--
-- 这里删的是 sys_menu 行（前端 buildRoutesFromMenus 与侧边栏都从它来）。
-- 前端配套改动：
--   1. router/index.ts     移除静态路由 + builtInViews 条目 + skip 列表项
--   2. stores/tab.ts       移除 tab 白名单里的 /bi/dashboard/wall
--   3. views/bi/dashboard/Index.vue  移除工具栏「展示墙」按钮与 openWall()
--   4. views/bi/dashboard/Wall.vue   整页删除
--   5. i18n                移除 menu.bi.dashboard.Wall / bi.dash.wall / bi.wall.*
--
-- sys_menu 没有 menu_id 外键表（只有 parent_id 自引用），且展示墙是叶子节点，
-- 所以直接删行不会留下悬挂引用。component 一并作为兜底判据，
-- 防止将来有人改了 path 却没改 component 时删不掉。

DELETE FROM sys_menu
 WHERE path = '/bi/dashboard/wall'
    OR component = 'bi/dashboard/Wall';