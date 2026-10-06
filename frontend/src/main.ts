import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import * as ElementPlusIconsVue from '@element-plus/icons-vue';
import 'element-plus/dist/index.css';
import 'element-plus/theme-chalk/dark/css-vars.css';
import 'nprogress/nprogress.css';
import AppRoot from './AppRoot.vue';
import router from './router';
import { i18n, getElementPlusLocale } from './i18n';
import './styles/global.css';
import './styles/theme.css';
import './styles/animations.css';

/**
 * el-table 操作列 fixed-right 单元格遮罩
 * element-plus 给 fixed 列 cell 写 inline style="background: inherit"，
 * 父级 current-row / hover 是半透明 rgba，inherit 后透底。
 * 解法：监听 cell 的 style 属性变化，立刻强制覆盖回不透明色。
 *
 * 颜色随主题模式切换：读 :root 的 --color-bg-elev / --color-bg / --color-primary-soft，
 * 主题切换后下一次 hover 即可生效（无需监听 mode 变更）。
 */
function readToken(name: string, fallback: string): string {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v || fallback;
}
function themeColors() {
  const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
  return {
    body: readToken('--color-bg-elev', isDark ? '#111827' : '#ffffff'),
    header: readToken('--color-bg', isDark ? '#0b1220' : '#f5f7fb'),
    hover: readToken('--color-primary-soft', isDark ? 'rgba(99,102,241,0.20)' : 'rgba(37,99,235,0.10)'),
  };
}

function paintFixedCells(box: HTMLElement) {
  const c = themeColors();
  box.style.setProperty('background', c.body, 'important');
  box.querySelectorAll<HTMLElement>('tr').forEach((tr) => {
    const isHeader = tr.closest('thead') !== null;
    const isHover = tr.matches(':hover');
    let bg = isHeader ? c.header : c.body;
    if (!isHeader && isHover) bg = c.hover;
    tr.querySelectorAll<HTMLElement>('td.el-table-fixed-column--right, th.el-table-fixed-column--right').forEach((cell) => {
      cell.style.setProperty('background', bg, 'important');
    });
  });
}

function applyFixedColumnMask() {
  document.querySelectorAll<HTMLElement>('.el-table__fixed-right').forEach((box) => {
    paintFixedCells(box);
    box.addEventListener('mouseover', () => paintFixedCells(box));
    box.addEventListener('mouseout', () => paintFixedCells(box));
  });
}

// 监听：element-plus 给 cell 改 inline style 时立刻重设
const mo = new MutationObserver((muts) => {
  for (const m of muts) {
    if (m.type === 'attributes'
      && (m.target as HTMLElement).classList?.contains('el-table-fixed-column--right')) {
      const cell = m.target as HTMLElement;
      const isHeader = cell.tagName === 'TH';
      const tr = cell.closest('tr');
      const isHover = tr?.matches(':hover') ?? false;
      const c = themeColors();
      let bg = isHeader ? c.header : c.body;
      if (!isHeader && isHover) bg = c.hover;
      cell.style.setProperty('background', bg, 'important');
    } else if (m.type === 'childList') {
      // 表格行数变化 / 重新渲染：重跑 paint
      const added = Array.from(m.addedNodes).some((n) => {
        return (n as HTMLElement).nodeType === 1 &&
          ((n as HTMLElement).classList?.contains('el-table__fixed-right') ||
           (n as HTMLElement).querySelector?.('.el-table__fixed-right'));
      });
      if (added) applyFixedColumnMask();
    }
  }
});
mo.observe(document.body, {
  childList: true, subtree: true, attributes: true,
  attributeFilter: ['style', 'class'],
});

// 初始兜底
if (document.readyState === 'complete') applyFixedColumnMask();
else window.addEventListener('load', applyFixedColumnMask);

const app = createApp(AppRoot);
for (const [key, comp] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, comp);
}
app.use(createPinia());
app.use(router);
app.use(i18n);
// 初始 Element Plus locale；切换语言时由 el-config-provider 实时换
app.use(ElementPlus, { locale: getElementPlusLocale() });

// 注意：不要在这里直接调 fetchMe / setupDynamicRoutes。
// 异步初始化会与 router 的初始 navigation 竞态，导致 Vue Router warn "no match"。
// 恢复动态路由统一由 router.beforeEach 完成（拿到 auth 后再恢复）。

// 字典缓存预加载：登录后由 Layout 在 afterEach 中触发；
// 未登录时跳过，store.load() 内部安全降级（HTTP 401/403 不影响其他页面）。
import { useDictStore } from './stores/dict';
import { useThemeStore } from './stores/theme';

const themeStore = useThemeStore();
themeStore.applyInitial();

const dictStore = useDictStore();
const token = localStorage.getItem('accessToken');
const refresh = localStorage.getItem('refreshToken');

/** 解码 JWT payload 的 perm 列表 —— 用于「没权限时跳过后台预加载」 */
function jwtPerms(t: string): string[] {
  try {
    const payload = t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const pad = payload.length % 4 ? 4 - (payload.length % 4) : 0;
    const json = atob(payload + '='.repeat(pad));
    return (JSON.parse(json).p as string[]) || [];
  } catch { return []; }
}

if (token && refresh) {
  // 仅在 refreshToken 也在时才预加载字典缓存：
  // accessToken 过期 + 没 refreshToken 时，调用会 401 且救不回来，反而报 console error
  // （http.ts 自动 refresh+retry 的路径只走 axios，本预加载只走一次）
  //
  // 仅当用户有对应 view perm 时才发请求 —— 否则用户连后台都没必要命中 API（也避免无意义 403）。
  // i18n 已统一收口到 TS 文件，无需再预加载 DB 翻译。
  const perms = jwtPerms(token);
  if (perms.includes('dict:view')) {
    dictStore.load().catch(() => { /* 静默失败，组件首次访问时再重试 */ });
  }
}

app.mount('#app');