import { defineStore } from 'pinia';
import { ref } from 'vue';
import { i18nApi } from '@/api/i18n';
import i18nInstance from '@/i18n';

const CACHE_KEY = 'fapiao_web.i18n-cache.v2';
const CACHE_TTL = 5 * 60 * 1000;

interface CachedMessages {
  loadedAt: number;
  data: Record<string, Record<string, any>>;
}

function readCache(): CachedMessages | null {
  try {
    const raw = localStorage.getItem(CACHE_KEY);
    if (!raw) return null;
    const c = JSON.parse(raw) as CachedMessages;
    if (Date.now() - c.loadedAt > CACHE_TTL) return null;
    return c;
  } catch {
    return null;
  }
}

function writeCache(data: Record<string, Record<string, any>>) {
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify({ loadedAt: Date.now(), data }));
  } catch {
    /* ignore quota */
  }
}

function clearCache() {
  try { localStorage.removeItem(CACHE_KEY); } catch { /* ignore */ }
}

/** 把 { locale: nestedMap } 注入到 vue-i18n 实例中，逐 locale 全量替换（保留 ts 作 fallback 已合在 base） */
function applyMessages(data: Record<string, Record<string, any>>) {
  if (!data || typeof data !== 'object') return;
  for (const [locale, nested] of Object.entries(data)) {
    if (!nested || typeof nested !== 'object') continue;
    // 先把 ts 已有 messages 与后端嵌套 messages 合并（前端 ts 作为 fallback）
    const base = (i18nInstance.global.messages as Record<string, any>)[locale] || {};
    const merged = deepMerge(base, nested);
    // setLocaleMessage 触发响应式更新
    (i18nInstance.global.setLocaleMessage as any)(locale, merged);
  }
}

/** 浅-深合并：b 覆盖 a（同 key 递归） */
function deepMerge(a: any, b: any): any {
  if (Array.isArray(a) || Array.isArray(b)) return b;
  if (a && typeof a === 'object' && b && typeof b === 'object') {
    const out: Record<string, any> = { ...a };
    for (const k of Object.keys(b)) {
      out[k] = deepMerge(a[k], b[k]);
    }
    return out;
  }
  return b !== undefined ? b : a;
}

export const useI18nStore = defineStore('i18n', () => {
  const loading = ref(false);
  const loadedAt = ref(0);
  const namespaces = ref<string[]>([]);
  const counts = ref<Record<string, number>>({});

  async function load(force = false): Promise<void> {
    if (!force) {
      const c = readCache();
      if (c) {
        applyMessages(c.data);
        loadedAt.value = c.loadedAt;
        return;
      }
    }
    loading.value = true;
    try {
      const data = await i18nApi.messagesAll();
      applyMessages(data);
      writeCache(data);
      loadedAt.value = Date.now();
    } catch (e) {
      // 静默失败：保留 ts 文件作为 fallback
      console.warn('[i18n] load failed, fallback to ts:', e);
    } finally {
      loading.value = false;
    }
  }

  async function refresh() {
    clearCache();
    await load(true);
  }

  async function refreshMeta() {
    try {
      namespaces.value = await i18nApi.namespaces();
      counts.value = await i18nApi.counts();
    } catch (e) {
      console.warn('[i18n] refreshMeta failed:', e);
    }
  }

  return {
    loading,
    loadedAt,
    namespaces,
    counts,
    load,
    refresh,
    refreshMeta,
  };
});