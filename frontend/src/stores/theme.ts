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
/** localStorage 缓存的归属账号 —— 同一浏览器换人登录时用来判定「这份缓存能不能信」 */
const OWNER_KEY = 'ifinbrain.theme.owner';

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

function loadOwner(): string {
  try { return localStorage.getItem(OWNER_KEY) || ''; } catch { return ''; }
}

function saveOwner(username: string) {
  try {
    if (username) localStorage.setItem(OWNER_KEY, username);
    else localStorage.removeItem(OWNER_KEY);
  } catch { /* ignore */ }
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

/**
 * 让 header / menu / primary 色板跟随「**生效**模式」。
 *
 * 用 effectiveMode（而不是 patch.mode 字面量）判定，所以三条路径都覆盖：
 *   light → dark / dark → light / auto（跟随系统）→ 解析出亮或暗
 * 只改「还是默认值」的那一项，用户手动调过色就不覆盖。
 */
function colorsForMode(s: ThemeSettings, mode: 'light' | 'dark'): ThemeSettings {
  const out = { ...s };
  if (mode === 'dark') {
    if (looksLightish(out.headerColor)) out.headerColor = DARK_DEFAULTS.headerColor;
    if (looksLightish(out.menuColor))   out.menuColor   = DARK_DEFAULTS.menuColor;
    if (out.primaryColor.toLowerCase() === LIGHT_DEFAULTS.primaryColor) {
      out.primaryColor = DARK_DEFAULTS.primaryColor;
    }
  } else {
    if (looksDarkish(out.headerColor)) out.headerColor = LIGHT_DEFAULTS.headerColor;
    if (looksDarkish(out.menuColor))   out.menuColor   = LIGHT_DEFAULTS.menuColor;
  }
  return out;
}

/**
 * 读取历史数据时的自愈：DB / localStorage 里可能存着 v1 登出 bug 留下的
 * 「浅色内容 + 深色默认侧栏」（或反向）坏状态，这里按生效模式翻回同侧默认色。
 *
 * 只认「**恰好等于** DARK/LIGHT 默认色」这一种，命中就整组换回；
 * 自定义色不动 —— 否则会覆盖用户「浅色内容 + 自选深色侧栏」的合法搭配。
 */
function reconcileStoredColors(s: ThemeSettings): ThemeSettings {
  const isDefault = (a: string, b: string) => a.trim().toLowerCase() === b.toLowerCase();
  const hitDark  = isDefault(s.headerColor, DARK_DEFAULTS.headerColor)  || isDefault(s.menuColor, DARK_DEFAULTS.menuColor);
  const hitLight = isDefault(s.headerColor, LIGHT_DEFAULTS.headerColor) || isDefault(s.menuColor, LIGHT_DEFAULTS.menuColor);
  if (effectiveMode(s) === 'light' && hitDark) {
    return { ...s, headerColor: LIGHT_DEFAULTS.headerColor, menuColor: LIGHT_DEFAULTS.menuColor };
  }
  if (effectiveMode(s) === 'dark' && hitLight) {
    return {
      ...s,
      headerColor: DARK_DEFAULTS.headerColor,
      menuColor: DARK_DEFAULTS.menuColor,
      primaryColor: isDefault(s.primaryColor, LIGHT_DEFAULTS.primaryColor) ? DARK_DEFAULTS.primaryColor : s.primaryColor,
    };
  }
  return s;
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
  const owner = ref<string>(loadOwner());
  const loaded = ref(false);

  const mode = computed<'light' | 'dark'>(() => effectiveMode(settings.value));

  let _mq: MediaQueryList | null = null;
  let _mqHandler: ((e: MediaQueryListEvent) => void) | null = null;
  let _syncTimer: ReturnType<typeof setTimeout> | null = null;
  /** 上一次落地的生效模式 —— auto 跟随系统切换时用它判断「亮↔暗」方向 */
  let _lastEffective: 'light' | 'dark' = effectiveMode(settings.value);

  /** 状态提交的唯一出口：内存 + localStorage + :root 三处一起落地 */
  function commit(next: ThemeSettings) {
    settings.value = next;
    _lastEffective = effectiveMode(next);
    saveToStorage(next);
    applyToRoot(next);
  }

  function applyInitial() {
    _lastEffective = effectiveMode(settings.value);
    applyToRoot(settings.value);
    installSystemThemeListener();
  }

  /**
   * 登录后从 DB 拉一次偏好。`ownerKey` = 当前登录用户名。
   *
   * 取值优先级：
   *   1. DB 有记录        → DB 权威（跨设备一致）
   *   2. DB 无记录 + 缓存属于同一个人 → 用本地缓存（离线兜底，不丢用户刚调的值）
   *   3. DB 无记录 + 缓存属于别人     → **回默认**，绝不让 A 的配色泄漏给 B
   */
  async function hydrate(ownerKey?: string) {
    let fromDb: ThemeSettings | null = null;
    try {
      const pref = await preferenceApi.get();
      if (pref?.themeJson) fromDb = migrateSettings(JSON.parse(pref.themeJson));
    } catch {
      /* 未登录或 403 —— 退到本地缓存 */
    }

    let next: ThemeSettings;
    if (fromDb) {
      next = fromDb;
    } else if (!ownerKey || !owner.value || owner.value === ownerKey) {
      next = { ...settings.value };
    } else {
      next = cloneDefault();
    }

    if (ownerKey) {
      owner.value = ownerKey;
      saveOwner(ownerKey);
    }
    commit(reconcileStoredColors(next));
    installSystemThemeListener();
    loaded.value = true;
  }

  async function update(patch: Partial<ThemeSettings>) {
    const merged = { ...settings.value, ...patch };
    /* smart dark switch —— 按「生效模式是否翻转」判定，覆盖 auto（跟随系统）这条路径 */
    const nextEff = effectiveMode(merged);
    commit(nextEff === _lastEffective ? merged : colorsForMode(merged, nextEff));
    scheduleSync();
  }

  /** StylePanel 的「重置」：只回默认字段，**保留当前模式**，色板按该模式取同侧默认
   *  —— 旧实现把 mode 归位成 light 却按 dark 取色，产出「浅色内容 + 深色侧栏」坏状态。 */
  async function reset() {
    const base: ThemeSettings = { ...cloneDefault(), mode: settings.value.mode };
    commit(colorsForMode(base, effectiveMode(base)));
    try { await preferenceApi.reset(); } catch { /* ignore */ }
  }

  /**
   * 登出清理：本地主题整体丢弃 + 立即落回默认配色。
   *
   * 不调后端 DELETE —— 偏好是**上一位用户自己的**设置，登出不该替他删掉，
   * 下次登录由 hydrate 从 DB 原样恢复。
   */
  function purge() {
    if (_syncTimer) { clearTimeout(_syncTimer); _syncTimer = null; }
    settings.value = cloneDefault();
    owner.value = '';
    _lastEffective = 'light';
    try {
      localStorage.removeItem(STORAGE_KEY);
      localStorage.removeItem(OWNER_KEY);
    } catch { /* ignore */ }
    applyToRoot(settings.value);
    loaded.value = false;
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
      if (settings.value.mode !== 'auto') return;
      const nowEff = effectiveMode(settings.value);
      /* 系统切了亮暗：侧栏/顶栏配色要跟着翻，否则 auto 下会出现深色侧栏配浅色内容 */
      if (nowEff === _lastEffective) applyToRoot(settings.value);
      else commit(colorsForMode(settings.value, nowEff));
    };
    mq.addEventListener?.('change', handler);
    _mq = mq;
    _mqHandler = handler;
  }

  return { settings, owner, loaded, mode, applyInitial, hydrate, update, reset, purge };
});

/* 给 settings.json 做向后兼容的迁移钩子（schema 演进时调用） */
export function migrateSettings(raw: any): ThemeSettings {
  const base = cloneDefault();
  if (!raw || typeof raw !== 'object') return base;
  return { ...base, ...raw };
}