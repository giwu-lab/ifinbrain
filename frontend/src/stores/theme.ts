import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
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

/** Smart dark switch：用户在亮色下未自定义 headerColor/menuColor 时，切到暗色自动用深色默认 */
const DARK_DEFAULTS = {
  headerColor: '#1a1f2e',
  menuColor: '#0f172a',
  primaryColor: '#60a5fa',
} as const;

const LIGHT_DEFAULTS = {
  headerColor: '#ffffff',
  menuColor: '#ffffff',
  primaryColor: '#2563eb',
} as const;

/** 颜色是否「看起来是白色」 —— 用于判断用户是否自定义过 */
function looksLightish(c: string): boolean {
  const { r, g, b, a } = parseColor(c);
  if (a < 0.9) return false;
  return (r + g + b) / 3 > 230;
}

/** 颜色是否「看起来是深色默认」—— 用于反向还原 */
function looksDarkish(c: string): boolean {
  const { r, g, b } = parseColor(c);
  return (r + g + b) / 3 < 60;
}

function saveToStorage(s: ThemeSettings) {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(s)); } catch { /* ignore */ }
}

/* ============================================================
 * 把 ThemeSettings 同步到 :root 的 CSS 变量 + data 属性
 *
 * 覆盖的关键变量：
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

  root.style.setProperty('--color-primary', s.primaryColor);
  root.style.setProperty('--color-primary-hover', darken(s.primaryColor, 0.12));
  root.style.setProperty('--color-primary-soft', withAlpha(s.primaryColor, (s.primaryOpacity / 100) * 0.10));
  root.style.setProperty('--color-primary-softer', withAlpha(s.primaryColor, (s.primaryOpacity / 100) * 0.04));

  root.style.setProperty('--el-color-primary', s.primaryColor);
  root.style.setProperty('--el-color-primary-light-3', s.primaryColor);
  root.style.setProperty('--el-color-primary-light-5', withAlpha(s.primaryColor, 0.4));
  root.style.setProperty('--el-color-primary-light-7', withAlpha(s.primaryColor, 0.2));
  root.style.setProperty('--el-color-primary-light-8', withAlpha(s.primaryColor, 0.12));
  root.style.setProperty('--el-color-primary-light-9', withAlpha(s.primaryColor, 0.06));
  root.style.setProperty('--el-color-primary-dark-2', darken(s.primaryColor, 0.15));

  const headerBg = withAlpha(s.headerColor, s.headerOpacity / 100);
  const menuBg = withAlpha(s.menuColor, s.menuOpacity / 100);
  root.style.setProperty('--header-bg', headerBg);
  root.style.setProperty('--sidebar-bg-actual', menuBg);

  const effective = effectiveMode(s);
  root.setAttribute('data-theme', effective);

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

function effectiveMode(s: ThemeSettings): 'light' | 'dark' {
  if (s.mode !== 'auto') return s.mode;
  if (typeof window === 'undefined') return 'light';
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/* ============================================================
 * Store — Pinia setup 写法：state/action 类型天然正确
 * ========================================================== */
export const useThemeStore = defineStore('theme', () => {
  const settings = ref<ThemeSettings>(loadFromStorage());
  const loaded = ref(false);

  const mode = computed<'light' | 'dark'>(() => effectiveMode(settings.value));

  let _mq: MediaQueryList | null = null;
  let _mqHandler: ((e: MediaQueryListEvent) => void) | null = null;
  let _syncTimer: ReturnType<typeof setTimeout> | null = null;

  function applyInitial() {
    applyToRoot(settings.value);
    installSystemThemeListener();
  }

  async function hydrate() {
    try {
      const pref = await preferenceApi.get();
      if (pref?.themeJson) {
        const parsed = JSON.parse(pref.themeJson);
        settings.value = { ...cloneDefault(), ...settings.value, ...parsed };
        saveToStorage(settings.value);
      }
    } catch {
      /* 未登录或 403 —— 保持 localStorage 状态 */
    } finally {
      applyToRoot(settings.value);
      installSystemThemeListener();
      loaded.value = true;
    }
  }

  async function update(patch: Partial<ThemeSettings>) {
    let next = { ...settings.value, ...patch };

    /* smart dark switch — 切到 dark：
     *  若 headerColor / menuColor 当前是白色默认 → 自动用深色默认；
     *  若 primaryColor 是 #2563eb（亮色默认）→ 提亮为 #60a5fa。
     * 切到 light 不反向还原：用户可能手动调过暗色配置，留给用户主动重置。 */
    if (patch.mode === 'dark' && settings.value.mode !== 'dark') {
      if (looksLightish(next.headerColor)) next.headerColor = DARK_DEFAULTS.headerColor;
      if (looksLightish(next.menuColor))   next.menuColor   = DARK_DEFAULTS.menuColor;
      if (next.primaryColor.toLowerCase() === LIGHT_DEFAULTS.primaryColor) {
        next.primaryColor = DARK_DEFAULTS.primaryColor;
      }
    }
    /* 反向：切回 light 时，若 header/menu 仍是「深色默认」 → 自动还原白色默认 */
    if (patch.mode === 'light' && settings.value.mode === 'dark') {
      if (looksDarkish(next.headerColor)) next.headerColor = LIGHT_DEFAULTS.headerColor;
      if (looksDarkish(next.menuColor))   next.menuColor   = LIGHT_DEFAULTS.menuColor;
    }

    settings.value = next;
    saveToStorage(settings.value);
    applyToRoot(settings.value);
    scheduleSync();
  }

  async function reset() {
    /* 根据当前 mode 选择合适的默认色组，避免用户在暗色下重置后变成白色 */
    const isDark = effectiveMode(settings.value) === 'dark';
    settings.value = {
      ...cloneDefault(),
      headerColor: isDark ? DARK_DEFAULTS.headerColor : LIGHT_DEFAULTS.headerColor,
      menuColor:   isDark ? DARK_DEFAULTS.menuColor   : LIGHT_DEFAULTS.menuColor,
      primaryColor: isDark ? DARK_DEFAULTS.primaryColor : LIGHT_DEFAULTS.primaryColor,
    };
    saveToStorage(settings.value);
    applyToRoot(settings.value);
    try { await preferenceApi.reset(); } catch { /* ignore */ }
  }

  function scheduleSync() {
    if (_syncTimer) clearTimeout(_syncTimer);
    _syncTimer = setTimeout(() => {
      try {
        preferenceApi.update(JSON.stringify(settings.value));
      } catch { /* 离线模式不打扰用户 */ }
    }, 500);
  }

  function installSystemThemeListener() {
    if (typeof window === 'undefined') return;
    if (_mq) return;
    const mq = window.matchMedia?.('(prefers-color-scheme: dark)');
    if (!mq) return;
    const handler = () => {
      if (settings.value.mode === 'auto') applyToRoot(settings.value);
    };
    mq.addEventListener?.('change', handler);
    _mq = mq;
    _mqHandler = handler;
  }

  return { settings, loaded, mode, applyInitial, hydrate, update, reset };
});

/* 给 settings.json 做向后兼容的迁移钩子（schema 演进时调用） */
export function migrateSettings(raw: any): ThemeSettings {
  const base = cloneDefault();
  if (!raw || typeof raw !== 'object') return base;
  return { ...base, ...raw };
}