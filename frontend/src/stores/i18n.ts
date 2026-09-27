import { defineStore } from 'pinia';
import { ref } from 'vue';
import { i18nApi } from '@/api/i18n';
import i18nInstance, { SUPPORTED_LOCALES } from '@/i18n';
import zhCN from '@/i18n/locales/zh-CN';
import enUS from '@/i18n/locales/en-US';

const CACHE_KEY = 'ifinbrain.i18n-cache.v52';
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

/**
 * 用嵌套的 TS base + DB response 做合并（两边的结构必须都是嵌套对象）。
 *
 * 注意：vue-i18n 内部把 messages 展平成 dot-notation，不能直接拿
 * `i18n.global.messages[locale]` 当 base，否则 deepMerge 会破坏结构。
 * 必须用 raw TS import 作为嵌套 base。
 */
function applyMessages(data: Record<string, Record<string, any>>) {
  if (!data || typeof data !== 'object') return;
  const tsBase: Record<string, Record<string, any>> = {
    'zh-CN': zhCN as any,
    'en-US': enUS as any,
  };
  for (const locale of SUPPORTED_LOCALES) {
    const nested = data[locale];
    const base = tsBase[locale] || {};
    const merged = nested ? deepMerge(base, nested) : base;
    try {
      // vue-i18n 编译消息时遇到非法占位符会抛 SyntaxError。
      // 单条坏消息不能让整个页面崩 —— 出错的那条用 ts 文件里的兜底版本。
      i18nInstance.global.setLocaleMessage(locale, merged);
    } catch (e: any) {
      console.warn(`[i18n] ${locale} merge failed, falling back to ts base only:`, e?.message);
      i18nInstance.global.setLocaleMessage(locale, base);
      // 把出问题的 message 单独 log 出来方便排查
      if (e?.message?.includes('placeholder')) {
        console.warn(`[i18n] hint: 检查 i18n 文案里的 {{ }} —— vue-i18n 把它当占位符了。`);
      }
    }
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