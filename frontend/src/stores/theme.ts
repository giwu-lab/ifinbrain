import { defineStore } from 'pinia';
import { preferenceApi } from '@/api/preference';

/* ============================================================
 * ThemeSettings：界面风格抽屉 (StylePanel) 的全部状态
 *
 * 默认值 = ifinbrain 当前视觉（蓝紫主色 + 浅色侧栏），
 * 首次打开抽屉看到的就是项目现状 —— 不突兀。
 * ========================================================== */

export type LayoutType = 'sidebar' | 'topnav' | 'header-sidebar' | 'floating-sidebar';
export type ThemeMode = 'light' | 'dark' | 'auto';
export type AnimationType = 'fade' | 'slide' | 'zoom' | 'fade-slide' | 'flip' | 'scale';

export interface ThemeSettings {
  // ── 主题动画 ──
  layout: LayoutType;
  primaryColor: string;
  primaryOpacity: number;       // 0..100
  headerColor: string;
  headerOpacity: number;
  menuColor: string;
  menuOpacity: number;
  enableAnimation: boolean;
  animationType: AnimationType;
  enableProgress: boolean;
  enableLoading: boolean;
  // ── 界面设置 ──
  mode: ThemeMode;
  collapseMenu: boolean;        // 菜单手风琴（一次只展开一个 DIR）
  fixedHeader: boolean;
  showBreadcrumb: boolean;
  showTabs: boolean;
  showLogo: boolean;
  showFullscreenBtn: boolean;
}

const DEFAULT_SETTINGS: ThemeSettings = {
  layout: 'sidebar',
  primaryColor: '#2563eb',
  primaryOpacity: 100,
  headerColor: '#ffffff',
  headerOpacity: 100,
  menuColor: '#ffffff',
  menuOpacity: 100,
  enableAnimation: true,
  animationType: 'fade',
  enableProgress: true,
  enableLoading: true,
  mode: 'light',
  collapseMenu: false,
  fixedHeader: true,
  showBreadcrumb: true,
  showTabs: true,
  showLogo: true,
  showFullscreenBtn: true,
};

const STORAGE_KEY = 'ifinbrain.theme.v1';

function loadFromStorage(): ThemeSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return cloneDefault();
    const parsed = JSON.parse(raw);
    return { ...cloneDefault(), ...parsed };
  } catch {
    return cloneDefault();
  }
}

function cloneDefault(): ThemeSettings {
  return { ...DEFAULT_SETTINGS };
}

function saveToStorage(s: ThemeSettings) {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(s)); } catch { /* ignore */ }
}

function clearStorage() {
  try { localStorage.removeItem(STORAGE_KEY); } catch { /* ignore */ }
}

/* ============================================================
 * 把 ThemeSettings 同步到 :root 的 CSS 变量
 *
 *  覆盖的关键变量：
 *   - --color-primary / --color-primary-hover / --color-primary-soft
 *   - --el-color-primary / --el-color-primary-light-3 ...
 *   - --color-bg / --color-bg-elev / --color-border / --color-text ...
 *   - --sidebar-bg / --header-bg
 *   - data-theme="light" | "dark" 用于 Element Plus
 *
 *  透明度字段影响 *-soft 衍生色，不影响 *-primary 本身（主色纯度由用户决定）
 * ========================================================== */
function applyToRoot(s: ThemeSettings) {
  if (typeof document === 'undefined') return;
  const root = document.documentElement;

  // 主色 + 衍生
  root.style.setProperty('--color-primary', s.primaryColor);
  root.style.setProperty('--color-primary-hover', darken(s.primaryColor, 0.12));
  root.style.setProperty('--color-primary-soft', withAlpha(s.primaryColor, (s.primaryOpacity / 100) * 0.10));
  root.style.setProperty('--color-primary-softer', withAlpha(s.primaryColor, (s.primaryOpacity / 100) * 0.04));

  // Element Plus 主题主色
  root.style.setProperty('--el-color-primary', s.primaryColor);
  root.style.setProperty('--el-color-primary-light-3', s.primaryColor);
  root.style.setProperty('--el-color-primary-light-5', withAlpha(s.primaryColor, 0.4));
  root.style.setProperty('--el-color-primary-light-7', withAlpha(s.primaryColor, 0.2));
  root.style.setProperty('--el-color-primary-light-8', withAlpha(s.primaryColor, 0.12));
  root.style.setProperty('--el-color-primary-light-9', withAlpha(s.primaryColor, 0.06));
  root.style.setProperty('--el-color-primary-dark-2', darken(s.primaryColor, 0.15));

  // 顶栏 / 菜单色
  const headerBg = withAlpha(s.headerColor, s.headerOpacity / 100);
  const menuBg = withAlpha(s.menuColor, s.menuOpacity / 100);
  root.style.setProperty('--header-bg', headerBg);
  root.style.setProperty('--sidebar-bg-actual', menuBg);

  // 主题模式
  const effective = effectiveMode(s);
  root.setAttribute('data-theme', effective);

  // 动画 class
  root.setAttribute('data-anim', s.enableAnimation ? s.animationType : 'none');
  root.setAttribute('data-layout', s.layout);
  root.setAttribute('data-progress', String(s.enableProgress));
  root.setAttribute('data-loading', String(s.enableLoading));
}

/** 解析 #rrggbb / #rgb / rgba(...) → {r,g,b,a} */
type RGBA = { r: number; g: number; b: number; a: number };

function parseColor(c: string): RGBA {
  c = c.trim();
  if (c.startsWith('#')) {
    let h = c.slice(1);
    if (h.length === 3) h = h.split('').map((x) => x + x).join('');
    const n = parseInt(h, 16);
    return { r: (n >> 16) & 0xff, g: (n >> 8) & 0xff, b: n & 0xff, a: 1 };
  }
  const m = c.match(/rgba?\(([^)]+)\)/i);
  if (m) {
    const parts = m[1].split(',').map((x) => parseFloat(x.trim()));
    return { r: parts[0] || 0, g: parts[1] || 0, b: parts[2] || 0, a: parts[3] ?? 1 };
  }
  return { r: 0, g: 0, b: 0, a: 1 };
}

function withAlpha(c: string, alpha: number): string {
  const { r, g, b } = parseColor(c);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

function darken(c: string, amount: number): string {
  const { r, g, b, a } = parseColor(c);
  const k = 1 - amount;
  return `rgba(${Math.round(r * k)}, ${Math.round(g * k)}, ${Math.round(b * k)}, ${a})`;
}

/** auto 模式读 prefers-color-scheme */
function effectiveMode(s: ThemeSettings): 'light' | 'dark' {
  if (s.mode !== 'auto') return s.mode;
  if (typeof window === 'undefined') return 'light';
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/* ============================================================
 * Store
 * ========================================================== */
export const useThemeStore = defineStore('theme', {
  state: (): { settings: ThemeSettings; loaded: boolean } => ({
    settings: loadFromStorage(),
    loaded: false,
  }),

  getters: {
    mode: (s): 'light' | 'dark' => effectiveMode(s.settings),
  },

  actions: {
    /** 启动时调用：apply localStorage 缓存，触发 UI 立刻反映。 */
    applyInitial() {
      applyToRoot(this.settings);
      this.installSystemThemeListener();
    },

    /** 登录成功时调用：DB 偏好 > localStorage > default（合并）。 */
    async hydrate() {
      try {
        const pref = await preferenceApi.get();
        if (pref?.themeJson) {
          const parsed = JSON.parse(pref.themeJson);
          this.settings = { ...cloneDefault(), ...this.settings, ...parsed };
          saveToStorage(this.settings);
        }
      } catch {
        /* 未登录或 403 —— 保持 localStorage 状态 */
      } finally {
        applyToRoot(this.settings);
        this.installSystemThemeListener();
        this.loaded = true;
      }
    },

    /** 任意 settings 修改入口：本地立即应用 + 写 localStorage + 节流同步 DB */
    async update(patch: Partial<ThemeSettings>) {
      this.settings = { ...this.settings, ...patch };
      saveToStorage(this.settings);
      applyToRoot(this.settings);
      this.scheduleSync();
    },

    /** 重置：default 落到 localStorage + DB */
    async reset() {
      this.settings = cloneDefault();
      saveToStorage(this.settings);
      applyToRoot(this.settings);
      try { await preferenceApi.reset(); } catch { /* ignore */ }
    },

    /** 节流（debounce）写 DB：500ms 内连续变更只发一次 PUT */
    scheduleSync() {
      if (this._syncTimer) clearTimeout(this._syncTimer);
      this._syncTimer = setTimeout(() => {
        try {
          preferenceApi.update(JSON.stringify(this.settings));
        } catch { /* ignore — 离线模式不打扰用户 */ }
      }, 500);
    },

    installSystemThemeListener() {
      if (typeof window === 'undefined') return;
      if (this._mq) return; // 已挂
      const mq = window.matchMedia?.('(prefers-color-scheme: dark)');
      if (!mq) return;
      const handler = () => {
        if (this.settings.mode === 'auto') {
          applyToRoot(this.settings);
        }
      };
      mq.addEventListener?.('change', handler);
      this._mq = mq;
      this._mqHandler = handler;
    },

    _mq: null as MediaQueryList | null,
    _mqHandler: null as ((e: MediaQueryListEvent) => void) | null,
    _syncTimer: null as ReturnType<typeof setTimeout> | null,
  } as any,
});

/* 给 settings.json 做向后兼容的迁移钩子（schema 演进时调用） */
export function migrateSettings(raw: any): ThemeSettings {
  const base = cloneDefault();
  if (!raw || typeof raw !== 'object') return base;
  return { ...base, ...raw };
}