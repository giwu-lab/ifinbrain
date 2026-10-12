-- 发布菜单不再占用模块的 component
--
-- 问题：发布报表 / 大屏时 MenuPublisher 把 component 写成模块自己的
--   'bi/report/View' / 'bi/dashboard/View'。前端 resolveMenuName() 把
--   `menu.{component}` 当成侧边栏显示名的**第一条**候选，于是命中
--   menu.bi.report.View = 「报表展示」—— 不管发布的是哪张报表，侧边栏都显示同一句话，
--   真正该显示的报表名（sys_menu.name）永远轮不到。
--
-- 档案库菜单（/documents/{slug}）没这个问题纯属侥幸：它们的 component 是
-- documents/list/Index，而 menu.documents.list.Index 这个 key 并不存在。
-- 这里把发布菜单改成同样的「数据行」形态 —— component 置空，i18n 候选全落空，
-- 自然回落到 sys_menu.name，也就是报表名 / 大屏名。
--
-- 前端配套：router/index.ts 的 buildRoutesFromMenus 改为按 path
-- 跳过 /^\/bi\/(report|dashboard)\/view\/\d+$/，不再依赖 component 判据。
--
-- 幂等：重复执行不会误伤 —— 这里只碰 path 带 /view/{数字} 的发布行。

UPDATE sys_menu
   SET component = NULL
 WHERE path ~ '^/bi/(report|dashboard)/view/[0-9]+$'
   AND component IS NOT NULL;