import { defineStore } from 'pinia';
import type { RouteLocationNormalized } from 'vue-router';

const STORAGE_KEY = 'ifinbrain.tabs.v2';

/** 不入 tab 标签条的路径前缀（单次访问页 / 鉴权页） */
const IGNORED_PREFIXES = [
  '/login', '/403', '/404',
  // BI 播放页 / 展示墙：全屏无 chrome 的单次访问页，不占 tab
  '/bi/dashboard/view', '/bi/dashboard/wall',
  // 报表播放页：同样是沉浸式单次访问页
  '/bi/report/view',
];

export interface Tab {
  /** 唯一 id：path 去 query。同一 path 不同 query 合并到同一 tab */
  id: string;
  /** 完整 path（含 query）：用于 router.push */
  fullPath: string;
  /** i18n key（如 'menu.system.user.Index'）；渲染时即时 t() 拿最新翻译 */
  nameKey: string;
  /** 锚定标签：不可关闭、刷新不丢 */
  affix: boolean;
  /** 是否可关闭（affix 永远 false） */
  closable: boolean;
}

interface TabState {
  tabs: Tab[];
  activeId: string;
  /** 每个 tab 的 refresh 计数器（path → number），bump 后 :key 变化 → 重渲染 */
  refreshKeys: Record<string, number>;
}

function isIgnored(path: string): boolean {
  return IGNORED_PREFIXES.some((p) => path === p || path.startsWith(p + '/') || path.startsWith(p + '?'));
}

function pathId(path: string): string {
  // 用纯 path 做 id；query 变化视为同一 tab
  return path.split('?')[0];
}

/** 从 route 推断 i18n key：优先 meta.component（对应 menu 命名空间），回退 meta.title */
function deriveNameKey(route: RouteLocationNormalized): string {
  const m = route.meta as { component?: string; title?: string; name?: string } | undefined;
  if (m?.component) return `menu.${m.component.replace(/\//g, '.')}`;
  if (m?.name) return m.name;
  return m?.title || route.path;
}

function loadFromStorage(): { tabs: Tab[]; activeId: string } | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const data = JSON.parse(raw) as { tabs: Tab[]; activeId: string };
    if (!Array.isArray(data.tabs)) return null;
    return data;
  } catch {
    return null;
  }
}

function saveToStorage(tabs: Tab[], activeId: string) {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ tabs, activeId }));
  } catch {
    /* quota / privacy mode：忽略 */
  }
}

function clearStorage() {
  try { sessionStorage.removeItem(STORAGE_KEY); } catch { /* ignore */ }
}

export const useTabStore = defineStore('tab', {
  state: (): TabState => {
    const persisted = loadFromStorage();
    return {
      tabs: persisted?.tabs ?? [],
      activeId: persisted?.activeId ?? '',
      refreshKeys: {},
    };
  },

  getters: {
    /** 给 <keep-alive :include> 用：当前 tab 列表里所有 path 对应的组件名。
     *  注：当前实现用 <keep-alive> 无 :include 全缓存，不依赖此 getter；保留以备后续切换。 */
    activeIds: (s): string[] => s.tabs.map((t) => t.id),
    hasTabs: (s: TabState) => s.tabs.length > 0,
  },

  actions: {
    /** 路由切换后调：登记 / 激活 tab */
    openTab(route: RouteLocationNormalized) {
      if (isIgnored(route.path)) return;

      const id = pathId(route.path);
      const fullPath = route.fullPath;
      const nameKey = deriveNameKey(route);
      const affix = route.path === '/dashboard';
      const closable = !affix;

      const existing = this.tabs.find((t) => t.id === id);
      if (existing) {
        // 同 path 不同 query：合并（更新 fullPath 与 nameKey）
        existing.fullPath = fullPath;
        existing.nameKey = nameKey;
      } else {
        this.tabs.push({ id, fullPath, nameKey, affix, closable });
      }
      this.activeId = id;
      this._persist();
    },

    /** 关闭一个 tab：若关的是 active，回退到左侧最近一个，否则跳 /dashboard */
    closeTab(id: string) {
      const idx = this.tabs.findIndex((t) => t.id === id);
      if (idx === -1) return;
      const tab = this.tabs[idx];
      if (!tab.closable) return; // affix 不可关

      this.tabs.splice(idx, 1);
      delete this.refreshKeys[id];

      if (this.activeId === id) {
        // 回退策略：找左侧最近一个；没有就 /dashboard
        const fallback = this.tabs[idx] || this.tabs[idx - 1] || this.tabs[0];
        this.activeId = fallback?.id || '';
      }
      this._persist();
      this._maybeNavigate();
    },

    /** 关闭除 id 与所有 affix 之外的全部 */
    closeOthers(id: string) {
      this.tabs = this.tabs.filter((t) => t.id === id || t.affix);
      // 清掉被关 tab 的 refreshKeys
      const keep = new Set(this.tabs.map((t) => t.id));
      for (const k of Object.keys(this.refreshKeys)) {
        if (!keep.has(k)) delete this.refreshKeys[k];
      }
      this.activeId = id;
      this._persist();
      this._maybeNavigate();
    },

    /** 关闭所有可关闭 tab（保留所有 affix） */
    closeAll() {
      const affixTabs = this.tabs.filter((t) => t.affix);
      this.tabs = affixTabs;
      this.refreshKeys = {};
      this.activeId = affixTabs[0]?.id || '';
      this._persist();
      this._maybeNavigate();
    },

    /** 刷新当前 tab：bump refreshKey → :key 变化 → 组件重挂载 */
    refreshTab(id?: string) {
      const target = id || this.activeId;
      if (!target) return;
      this.refreshKeys[target] = (this.refreshKeys[target] || 0) + 1;
    },

    /** 设置 active（用户点击 tab） */
    setActive(id: string) {
      if (this.activeId === id) return;
      const tab = this.tabs.find((t) => t.id === id);
      if (!tab) return;
      this.activeId = id;
      this._persist();
    },

    /** 清空一切（登出时） */
    reset() {
      this.tabs = [];
      this.activeId = '';
      this.refreshKeys = {};
      clearStorage();
    },

    /** 内部：sessionStorage 持久化 */
    _persist() {
      saveToStorage(this.tabs, this.activeId);
    },

    /** 内部：tab 关闭后若影响 active，跳转到目标 tab */
    _maybeNavigate() {
      // 避免循环 import：动态 require router
      // 这里只更新 activeId，由调用方决定是否实际跳转
      // 真正的跳转发生在 TabBar.vue 的 onClose 回调里（因为那里有 router 实例）
    },
  },
});

export { isIgnored, pathId, deriveNameKey };