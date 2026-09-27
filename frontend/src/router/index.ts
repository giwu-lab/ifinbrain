import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import nprogress from 'nprogress';
import axios from 'axios';
import { useAuthStore } from '@/stores/auth';
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
  { path: '/data-mgmt/query/new', component: () => import('@/views/data-mgmt/query/Detail.vue'),
    meta: { title: 'New query', permission: 'data:query:create' } },
  { path: '/data-mgmt/query/:id(\\d+)', component: () => import('@/views/data-mgmt/query/Detail.vue'),
    meta: { title: 'Query', permission: 'data:query:view' } },
  // 一键生成的查询列表页（不走 sys_menu 静态 path 匹配，因为 path 里是动态 menuId）
  { path: '/data-mgmt/query-page/:menuId(\\d+)', component: () => import('@/views/data-mgmt/query-page/Index.vue'),
    meta: { title: 'Query Page', permission: 'data:query:view' } },
  // 页面设计器（不走 sys_menu；编辑/预览路径动态）
  { path: '/design/page/new', component: () => import('@/views/design/page/Index.vue'),
    meta: { title: 'Page Designer', permission: 'design:page:update' } },
  { path: '/design/page/:id(\\d+)/edit', component: () => import('@/views/design/page/Editor.vue'),
    meta: { title: 'Edit Page', permission: 'design:page:update' } },
  { path: '/design/page/:id(\\d+)/preview', component: () => import('@/views/design/page/Preview.vue'),
    meta: { title: 'Preview Page', permission: 'design:page:view' } },
  // 运行时门户页（登录后按角色命中）
  { path: '/portal/:code', component: () => import('@/views/portal/Index.vue'),
    meta: { title: 'Portal' } },
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
  'data-mgmt/datasource/Index':  () => import('@/views/data-mgmt/datasource/Index.vue'),
  'data-mgmt/query/Index':       () => import('@/views/data-mgmt/query/Index.vue'),
  'data-mgmt/query/Detail':      () => import('@/views/data-mgmt/query/Detail.vue'),
  'data-mgmt/query-page/Index':  () => import('@/views/data-mgmt/query-page/Index.vue'),
  'documents/archive/Index':     () => import('@/views/documents/archive/Index.vue'),
  'documents/list/Index':        () => import('@/views/documents/list/Index.vue'),
  'design/page/Index':           () => import('@/views/design/page/Index.vue'),
  'design/flow/Index':           () => import('@/views/design/flow/Index.vue')
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
    // 页面设计器：精确菜单 path=/design/page 已由下方静态路由接管（new/edit/preview 走参数路由）
    if (compKey === 'design/page/Index') continue;
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
  nprogress.start();
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

router.afterEach(() => nprogress.done());

export default router;