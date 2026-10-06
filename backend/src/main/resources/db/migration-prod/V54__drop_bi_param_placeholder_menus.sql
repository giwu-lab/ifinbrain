-- V54: 移除 BI 大屏的「设计」「展示」两条菜单行
--
-- 背景（V53 留下的缺陷）：
--   V53 给这两条菜单写了带占位符的 path —— `/bi/dashboard/design/:id` 与
--   `/bi/dashboard/view/:id`。但 sys_menu.path 是**真实 URL**，不是路由模板：
--   点侧边栏就是真的导航到字面量 `/bi/dashboard/design/:id`，
--   Designer.vue 里 Number(':id') = NaN → 前端请求 /api/bi/dashboard/NaN
--   → MethodArgumentTypeMismatchException → 500「服务器内部错误」。
--
-- 为什么直接删而不是改 path：
--   这两条本质是**操作入口**而非列表页 —— 从「大屏管理」列表页的
--   编辑 / 预览 图标按钮进入才是正确路径（见 views/bi/dashboard/Index.vue）。
--   侧边栏不需要常驻的「打开第 N 个大屏的设计器」入口。
--   路由本身仍保留：/bi/dashboard/design/:id 与 /bi/dashboard/view/:id
--   是 staticRoutes（router/index.ts），供列表页按钮与外部深链使用，不受影响。
--
-- 保留：
--   · /bi/dashboard            大屏管理（列表页，侧边栏主入口）
--   · /bi/dashboard/wall       展示墙（无路径参数，是真实独立页面）
--
-- 幂等：按 path + type 判存在，重复执行安全。

DELETE FROM sys_menu
 WHERE path = '/bi/dashboard/design/:id'
   AND type = 'MENU';

DELETE FROM sys_menu
 WHERE path = '/bi/dashboard/view/:id'
   AND type = 'MENU';