import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import nprogress from 'nprogress';
import { useAuthStore } from '@/stores/auth';
import type { MenuNode } from '@/types';

nprogress.configure({ showSpinner: false });

const staticRoutes: RouteRecordRaw[] = [
  { path: '/login', component: () => import('@/views/login/Index.vue'), meta: { public: true, title: 'Login' } },
  { path: '/403',   component: () => import('@/views/error/403.vue'),         meta: { public: true, title: 'Forbidden' } },
  { path: '/404',   component: () => import('@/views/error/404.vue'),         meta: { public: true, title: 'Not Found' } }
];

const builtInViews: Record<string, () => Promise<unknown>> = {
  'dashboard/Index': () => import('@/views/dashboard/Index.vue'),
  'invoice/Upload': () => import('@/views/invoice/Upload.vue'),
  'invoice/List': () => import('@/views/invoice/List.vue'),
  'system/user/Index': () => import('@/views/system/user/Index.vue'),
  'system/role/Index': () => import('@/views/system/role/Index.vue'),
  'system/menu/Index': () => import('@/views/system/menu/Index.vue'),
  'system/storage/Index': () => import('@/views/system/storage/Index.vue'),
  'system/ocr/Index': () => import('@/views/system/ocr/Index.vue'),
  'system/dict/Index': () => import('@/views/system/dict/Index.vue')
};

function buildRoutesFromMenus(menus: MenuNode[]): RouteRecordRaw[] {
  // 关键：每个 menu route 都是一个**独立的顶层路径**，直接渲染对应的 page 组件。
  // Layout (sidebar / header) 在 App.vue 顶层统一包裹，不需要把 Layout 作为 route component。
  // 这样 addRoute 后 vue-router 匹配路径稳定，没有父子嵌套问题，也避免了 Layout 重复渲染。
  const routes: RouteRecordRaw[] = [];
  for (const m of menus) {
    if (m.type === 'DIR') {
      const dirPath = m.path || '';
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
  if (to.meta.permission && !auth.hasPerm(to.meta.permission as string)) {
    return next('/403');
  }
  next();
});

router.afterEach(() => nprogress.done());

export default router;