import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import nprogress from 'nprogress';
import axios from 'axios';
import { useAuthStore } from '@/stores/auth';
import { useTabStore } from '@/stores/tab';
import { useThemeStore } from '@/stores/theme';
import type { MenuNode } from '@/types';

nprogress.configure({ showSpinner: false });

const baseURL = import.meta.env.VITE_API_BASE || '/api';

/**
 * 解 JWT payload（不验签；只用于对比 perm 数）。
 * 解析失败返回 null。
 */
function decodeJwtPayload(token: string): { p?: string[] } | null {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const padded = parts[1] + '='.repeat((4 - parts[1].length % 4) % 4);
    return JSON.parse(atob(padded.replace(/-/g, '+').replace(/_/g, '/')));
  } catch {
    return null;
  }
}

/**
 * 如果 JWT 的 perm 集是过期的（DB 通过 /me 返回的 perm 集更大），
 * 调一次 /auth/refresh 拿新 JWT，避免后续 API 撞 403。
 *
 * 触发场景：用户登录后，DB 后续新增了权限（典型场景：Flyway V{n} 加新权限），
 * 此时旧 JWT 的 "p" claim 缺新权限，但 auth.user.permissions（来自 /me）已含。
 */
async function refreshIfStale(auth: ReturnType<typeof useAuthStore>): Promise<void> {
  if (!auth.token) return;
  const refresh = localStorage.getItem('refreshToken');
  if (!refresh) return;

  const jwtPerms = new Set(decodeJwtPayload(auth.token)?.p || []);
  const dbPerms = new Set(auth.user?.permissions || []);

  // DB 有但 JWT 没有 → JWT 过期
  let stale = false;
  for (const p of dbPerms) {
    if (!jwtPerms.has(p)) { stale = true; break; }
  }
  if (!stale) return;

  try {
    const r = await axios.post(`${baseURL}/auth/refresh`, { refreshToken: refresh });
    const data = r.data?.data;
    if (data?.accessToken) {
      localStorage.setItem('accessToken', data.accessToken);
      if (data.refreshToken) localStorage.setItem('refreshToken', data.refreshToken);
      auth.token = data.accessToken; // 触发 computed 重新计算（permissions 已通过 /me 更新）
      console.info('[router] JWT stale, refreshed silently');
    }
  } catch (e) {
    // refresh 失败 → 让后续 401 / 403 拦截器接管
    console.warn('[router] silent JWT refresh failed:', e);
  }
}

const staticRoutes: RouteRecordRaw[] = [
  { path: '/login', component: () => import('@/views/login/Index.vue'), meta: { public: true, title: 'Login' } },
  { path: '/403',   component: () => import('@/views/error/403.vue'),         meta: { public: true, title: 'Forbidden' } },
  { path: '/404',   component: () => import('@/views/error/404.vue'),         meta: { public: true, title: 'Not Found' } },
  // 查询编辑（不走 sys_menu，因为 :id 路径无法在 menu 里静态声明）
  // meta.name / meta.component 不能省：layout 的面包屑与 tab 标题都靠
  // deriveNameKey → `menu.<component>` 去查 i18n key；缺了就只剩 meta.title
  // 那个硬编码英文，中文界面下面包屑会直接显示 "Query"（AGENTS §2：不允许 fallback 到字面量）。
  { path: '/data-mgmt/query/new', component: () => import('@/views/data-mgmt/query/Detail.vue'),
    meta: { title: 'New query', permission: 'data:query:create',
            name: 'Query Editor', component: 'data-mgmt/query/Detail', path: '/data-mgmt/query/new' } },
  { path: '/data-mgmt/query/:id(\\d+)', component: () => import('@/views/data-mgmt/query/Detail.vue'),
    meta: { title: 'Query', permission: 'data:query:view',
            name: 'Query Editor', component: 'data-mgmt/query/Detail', path: '/data-mgmt/query/:id(\\d+)' } },
  // 一键生成的查询列表页（不走 sys_menu 静态 path 匹配，因为 path 里是动态 menuId）
  { path: '/data-mgmt/query-page/:menuId(\\d+)', component: () => import('@/views/data-mgmt/query-page/Index.vue'),
    meta: { title: 'Query Page', permission: 'data:query:view',
            name: 'Query Page', component: 'data-mgmt/query-page/Index', path: '/data-mgmt/query-page/:menuId(\\d+)' } },
  // 流程设计器（不走 sys_menu；list/new/edit/preview 路径动态）
  { path: '/design/flow', component: () => import('@/views/design/flow/Index.vue'),
    meta: { title: 'Flow Designer', permission: 'design:flow:view', name: 'Flow Designer', component: 'design/flow/Index', path: '/design/flow' } },
  { path: '/design/flow/new', component: () => import('@/views/design/flow/Designer.vue'),
    meta: { title: 'New Flow', permission: 'design:flow:create', name: 'New Flow', component: 'design/flow/Index', path: '/design/flow/new' } },
  { path: '/design/flow/:id(\\d+)/edit', component: () => import('@/views/design/flow/Designer.vue'),
    props: true,
    meta: { title: 'Edit Flow', permission: 'design:flow:update', name: 'Edit Flow', component: 'design/flow/Index', path: '/design/flow/:id/edit' } },
  { path: '/design/flow/:id(\\d+)/preview', component: () => import('@/views/design/flow/Preview.vue'),
    props: true,
    meta: { title: 'Preview Flow', permission: 'design:flow:view', name: 'Preview Flow', component: 'design/flow/Index', path: '/design/flow/:id/preview' } },
  // 流程运行时（待办 / 我发起的 / 发起流程）
  { path: '/design/flow-runtime', component: () => import('@/views/design/flow/Workflow.vue'),
    meta: { title: 'Flow Runtime', permission: 'design:flow:deploy', name: 'Flow Runtime', component: 'design/flow-runtime/Index', path: '/design/flow-runtime' } },
  // 表单设计器（不走 sys_menu，list/new/edit/preview 路径动态）
  // meta.component 指到 design/form/Index：tab 标签走 menu.design.form.Index（i18n 可翻译）；
  // 4 条路由共用一个 component key —— tab 名相同，由 URL 后缀区分，与 /documents/:slug 模式一致。
  { path: '/design/form', component: () => import('@/views/design/form/Index.vue'),
    meta: { title: 'Form Designer', permission: 'design:form:view', name: 'Form Designer', component: 'design/form/Index', path: '/design/form' } },
  { path: '/design/form/new', component: () => import('@/views/design/form/Designer.vue'),
    meta: { title: 'New Form', permission: 'design:form:create', name: 'New Form', component: 'design/form/Index', path: '/design/form/new' } },
  { path: '/design/form/:id(\\d+)/edit', component: () => import('@/views/design/form/Designer.vue'),
    props: true,
    meta: { title: 'Edit Form', permission: 'design:form:update', name: 'Edit Form', component: 'design/form/Index', path: '/design/form/:id/edit' } },
  { path: '/design/form/:id(\\d+)/preview', component: () => import('@/views/design/form/Preview.vue'),
    props: true,
    meta: { title: 'Preview Form', permission: 'design:form:view', name: 'Preview Form', component: 'design/form/Index', path: '/design/form/:id/preview' } },
  // 档案管理 → 笔记：列表 / 新建 / 编辑三页（不走 sys_menu 的 :id 路径声明）
  // 必须声明在 `/documents/:slug` **之前**：vue-router 按 specificity 匹配，
  // 静态段本就赢过参数段，但显式放在前面能避免以后有人调整顺序时静默失效。
  // meta.component 决定面包屑 / tab 的 i18n key（menuName() → `menu.<component>`）。
  // 三页给三个 key：列表「笔记管理」、新建「新建笔记」、编辑「编辑笔记」——
  // 挤成同一个 key 的话，从列表点进编辑页，面包屑和 tab 都还是「笔记管理」，
  // 分不清当前在哪一页（/design/form 的 new|edit 就有这个毛病，这里不复制）。
  { path: '/documents/notes', component: () => import('@/views/documents/notes/Index.vue'),
    meta: { title: 'Notes', permission: 'note:view', name: 'Notes', component: 'documents/notes/Index', path: '/documents/notes' } },
  { path: '/documents/notes/new', component: () => import('@/views/documents/notes/Edit.vue'),
    meta: { title: 'New Note', permission: 'note:create', name: 'New Note', component: 'documents/notes/New', path: '/documents/notes/new' } },
  { path: '/documents/notes/:id(\\d+)', component: () => import('@/views/documents/notes/Edit.vue'),
    meta: { title: 'Note', permission: 'note:view', name: 'Note Editor', component: 'documents/notes/E', path: '/documents/notes/:id' } },
// 档案管理 → 档案库管理（不走 sys_menu，由静态路由接管；与 /documents/:slug 一致）
  // 老路径 /documents/category 重定向到 /documents/archive（兼容老书签）
  { path: '/documents/category', redirect: '/documents/archive' },
  { path: '/documents/archive', component: () => import('@/views/documents/archive/Index.vue'),
    meta: { title: 'Archive Library', permission: 'documents:view', name: 'Archive Library', component: 'documents/archive/Index', path: '/documents/archive' } },
  // 档案管理 → 档案库列表页（catch-all；运行时由 sys_menu 添加的 path 会因 component 同名被 skip，
  // 全部走这条静态路由 → 永远生效）
  // 注意：不挂 meta.permission —— 档案库级 view 由 page 内 loadBySlug() + requireLibraryPerm() 校验，
  // 否则 router 守卫会把没全局 documents:view 但有 documents:{slug}:view 的用户挡在 /403。
  { path: '/documents/:slug', component: () => import('@/views/documents/list/Index.vue'),
    meta: { title: 'Documents', name: 'Documents', component: 'documents/list/Index', path: '/documents' } },
  // 顶级 DIR (/documents) 不挂路由 —— DIR 只是文件夹容器，点击只展开/收起，不应跳转

  // ==================== BI 大屏（不走 sys_menu 精确路径，静态路由接管） ====================
  // 设计器 new / :id 两个入口都用同一个组件，id=非数字时视为新建
  { path: '/bi/dashboard/design/:id', component: () => import('@/views/bi/dashboard/Designer.vue'),
    meta: { title: 'BI Designer', permission: 'bi:dashboard:update', name: 'BI Designer', component: 'bi/dashboard/Designer', path: '/bi/dashboard/design' } },
  // 播放页 / 展示墙走 tab 白名单（stores/tab.ts IGNORED_PREFIXES），不进 tab 条
  // immersive=true → App.vue 用裸 router-view 渲染，脱离 BasicLayout 全屏。
  // 注意不能用 meta.public（那会连带跳过鉴权守卫）。
  { path: '/bi/dashboard/view/:id(\\d+)', component: () => import('@/views/bi/dashboard/View.vue'),
    meta: { title: 'BI View', permission: 'bi:dashboard:view', immersive: true, name: 'BI View', component: 'bi/dashboard/View', path: '/bi/dashboard/view' } },
  { path: '/bi/dashboard/wall', component: () => import('@/views/bi/dashboard/Wall.vue'),
    meta: { title: 'BI Wall', permission: 'bi:dashboard:view', immersive: true, name: 'BI Wall', component: 'bi/dashboard/Wall', path: '/bi/dashboard/wall' } },

  // ==================== 报表（同样静态路由接管） ====================
  // 设计器：new 与 :id 共用 Design.vue（id=非数字时视为新建，与大屏设计器一致）
  { path: '/bi/report/design/:id', component: () => import('@/views/bi/report/Design.vue'),
    meta: { title: 'Report Designer', permission: 'report:update', name: 'Report Designer', component: 'bi/report/Design', path: '/bi/report/design' } },
  // 报表播放页：沉浸式全屏（查询头 + 虚拟滚动表格，无侧边栏 chrome）
  { path: '/bi/report/view/:id(\\d+)', component: () => import('@/views/bi/report/View.vue'),
    meta: { title: 'Report View', permission: 'report:view', immersive: true, name: 'Report View', component: 'bi/report/View', path: '/bi/report/view' } },
];

const builtInViews: Record<string, () => Promise<unknown>> = {
  'dashboard/Index': () => import('@/views/dashboard/Index.vue'),
  'invoice/Upload': () => import('@/views/invoice/Upload.vue'),
  'invoice/List': () => import('@/views/invoice/List.vue'),
  'invoice/parser/Index': () => import('@/views/invoice/parser/Index.vue'),
  'system/user/Index': () => import('@/views/system/user/Index.vue'),
  'system/role/Index': () => import('@/views/system/role/Index.vue'),
  'system/menu/Index': () => import('@/views/system/menu/Index.vue'),
  'system/storage/Index': () => import('@/views/system/storage/Index.vue'),
  'system/dict/Index': () => import('@/views/system/dict/Index.vue'),
  'system/dept/Index': () => import('@/views/system/dept/Index.vue'),
  'data-mgmt/datasource/Index':  () => import('@/views/data-mgmt/datasource/Index.vue'),
  'data-mgmt/query/Index':       () => import('@/views/data-mgmt/query/Index.vue'),
  'data-mgmt/query/Detail':      () => import('@/views/data-mgmt/query/Detail.vue'),
  'data-mgmt/query-page/Index':  () => import('@/views/data-mgmt/query-page/Index.vue'),
  'data-mgmt/import/Index':      () => import('@/views/data-mgmt/import/Index.vue'),
  'documents/archive/Index':     () => import('@/views/documents/archive/Index.vue'),
  'documents/list/Index':        () => import('@/views/documents/list/Index.vue'),
  'documents/notes/Index':        () => import('@/views/documents/notes/Index.vue'),
  'documents/notes/Edit':         () => import('@/views/documents/notes/Edit.vue'),
  'design/flow/Index':           () => import('@/views/design/flow/Index.vue'),
  'design/form/Index':           () => import('@/views/design/form/Index.vue'),
  'todo/task/Index':             () => import('@/views/todo/task/Index.vue'),
  'bi/dashboard/Index':          () => import('@/views/bi/dashboard/Index.vue'),
  'bi/dashboard/Designer':       () => import('@/views/bi/dashboard/Designer.vue'),
  'bi/dashboard/View':           () => import('@/views/bi/dashboard/View.vue'),
  'bi/dashboard/Wall':           () => import('@/views/bi/dashboard/Wall.vue'),
  'bi/report/Index':             () => import('@/views/bi/report/Index.vue'),
  'bi/report/Design':            () => import('@/views/bi/report/Design.vue'),
  'bi/report/View':              () => import('@/views/bi/report/View.vue'),
};

function buildRoutesFromMenus(menus: MenuNode[]): RouteRecordRaw[] {
  // 关键：每个 menu route 都是一个**独立的顶层路径**，直接渲染对应的 page 组件。
  // Layout (sidebar / header) 在 App.vue 顶层统一包裹，不需要把 Layout 作为 route component。
  // 这样 addRoute 后 vue-router 匹配路径稳定，没有父子嵌套问题，也避免了 Layout 重复渲染。
  const routes: RouteRecordRaw[] = [];
  for (const m of menus) {
    if (m.type === 'DIR') {
      const dirPath = m.path || '';
      // /documents DIR 由静态路由接管（/documents → /documents/archive；
      // 档案库页 /documents/{slug} 由另一条静态路由接管），不在此处挂 redirect，避免双路由冲突
      if (dirPath === '/documents') {
        routes.push(...buildRoutesFromMenus(m.children || []));
        continue;
      }
      const firstChildPath = (m.children || [])[0]?.path || dirPath;
      // DIR 路径与首个 child 路径相同（典型：单子菜单 DIR），挂 redirect 会自指向；
      // 直接让 MENU 子项接管该路径即可（与 /documents 同模式）。
      if (firstChildPath === dirPath) {
        routes.push(...buildRoutesFromMenus(m.children || []));
        continue;
      }
      // DIR 本身：只挂个 redirect，让访问 /invoice 时跳到第一个子项
      routes.push({
        path: dirPath,
        redirect: firstChildPath,
        meta: { isDir: true }
      });
      routes.push(...buildRoutesFromMenus(m.children || []));
      continue;
    }
    if (m.type === 'MENU' && m.path) {
      const compKey = m.component || '';
      // 发布的查询页面（path 形如 /data-mgmt/query-page/50）由静态参数化路由
      // /data-mgmt/query-page/:menuId(\d+) 处理 —— 跳过精确路径注册，避免抢路由
      if (compKey === 'data-mgmt/query-page/Index') continue;
      // 档案管理 → 档案库列表页：所有档案库共用同一组件 `documents/list/Index`，运行时由 admin
      // 创建档案库时插入的 sys_menu 行 path = /documents/{slug} 也用此 component。
      // 跳过精确路径注册，让 `/documents/:slug` 静态路由统一接管（避免双路由冲突）。
      if (compKey === 'documents/list/Index') continue;
      // 档案管理 → 档案库：静态路由 `/documents/archive` 接管，跳过精确路径注册避免双路由冲突
      if (compKey === 'documents/archive/Index') continue;
      // 档案管理 → 笔记：列表 / 新建 / 编辑三条静态路由接管
      // （新建与编辑的 path 带 :id 占位，精确注册会生成非法路由）
      if (compKey === 'documents/notes/Index') continue;
      // 表单设计器：精确菜单 path=/design/form 已由下方静态路由接管（new/edit/preview 走参数路由）
      if (compKey === 'design/form/Index') continue;
      // BI 大屏：Designer / View / Wall 三条菜单行都由静态参数化路由接管
      // （sys_menu path 带 :id 占位，精确注册会生成非法路由）→ 跳过避免双路由冲突。
      // `bi/dashboard/Index`（path=/bi/dashboard）正常注册，是侧边栏真正要跳转的入口。
      if (compKey === 'bi/dashboard/Designer') continue;
      if (compKey === 'bi/dashboard/View') continue;
      if (compKey === 'bi/dashboard/Wall') continue;
      // 报表：Design / View 由静态参数化路由接管（同 BI 大屏的理由）。
      // `bi/report/Index`（path=/bi/report）正常注册，是侧边栏入口。
      if (compKey === 'bi/report/Design') continue;
      if (compKey === 'bi/report/View') continue;
      const component = builtInViews[compKey] || (() => import('@/views/error/404.vue'));
      routes.push({
        path: m.path,
        component,
        meta: { title: m.name, icon: m.icon, permission: m.permission, name: m.name, component: m.component, path: m.path }
      });
    }
  }
  return routes;
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    ...staticRoutes,
    { path: '/', redirect: '/dashboard' }
  ]
});

export async function setupDynamicRoutes(menus: MenuNode[]) {
  const dynamic = buildRoutesFromMenus(menus);
  console.log('[router] buildRoutesFromMenus returned', dynamic.length, 'routes:', dynamic.map(r => r.path));
  const seen = new Set<string>();
  for (const r of dynamic) {
    const path = r.path as string;
    if (seen.has(path)) continue;
    seen.add(path);
    if (!router.hasRoute(path)) {
      router.addRoute({ ...r, name: path });
    }
  }
  if (!router.hasRoute('/:pathMatch(.*)*')) {
    router.addRoute({ path: '/:pathMatch(.*)*', name: '/:pathMatch(.*)*', redirect: '/404' });
  }
  console.log('[router] after setupDynamicRoutes, total routes:', router.getRoutes().length);
}

router.beforeEach(async (to, _from, next) => {
  // 进度条：受 theme store 控制
  try { useThemeStore().settings.enableProgress && nprogress.start(); } catch { nprogress.start(); }
  const auth = useAuthStore();
  if (to.meta.public) return next();
  if (!auth.isLogged) {
    return next({ path: '/login', query: { redirect: to.fullPath } });
  }
  if (auth.isLogged && !auth.user) {
    try {
      const info = await auth.fetchMe();
      await setupDynamicRoutes(info.menus);
      // 关键：刷新后首访问的路径在动态路由注册前已被 resolve，
      // 这里再次 push(to) 让 vue-router 用新路由表重新匹配并渲染。
      // 第二次 beforeEach 时 auth.user 已存在，不会重复注册。
      if (to.path !== '/login') {
        return next({ ...to, replace: true });
      }
    } catch {
      auth.clear();
      return next({ path: '/login' });
    }
  }
  // ★ 自愈：检测 JWT perm 与 DB perm 是否一致；不一致就静默 refresh JWT
  // （典型场景：Flyway V{n} 加了新权限，老登录的 JWT 还缺）
  await refreshIfStale(auth);
  if (to.meta.permission && !auth.hasPerm(to.meta.permission as string)) {
    return next('/403');
  }
  next();
});

router.afterEach((to) => {
  nprogress.done();
  // 同步多标签页：登录后所有跳转都登记
  // ignore 列表（/login /403 /404）在 tabStore.openTab 内部判断
  try {
    useTabStore().openTab(to);
  } catch {
    /* 路由初始化阶段 store 还没注入，忽略 */
  }
});

export default router;