import { createI18n, type I18n } from 'vue-i18n';
import zhCN from './locales/zh-CN';
import enUS from './locales/en-US';
import zhCnEl from 'element-plus/es/locale/lang/zh-cn';
import enEl from 'element-plus/es/locale/lang/en';

const STORAGE_KEY = 'ifinbrain.lang';

export type Locale = 'zh-CN' | 'en-US';
export const SUPPORTED_LOCALES: Locale[] = ['zh-CN', 'en-US'];

function detectInitialLocale(): Locale {
  if (typeof window === 'undefined') return 'zh-CN';
  const stored = localStorage.getItem(STORAGE_KEY);
  if (stored === 'zh-CN' || stored === 'en-US') return stored as Locale;
  const langs = window.navigator.languages || [window.navigator.language];
  for (const l of langs) {
    if (l.toLowerCase().startsWith('zh')) return 'zh-CN';
  }
  return 'en-US';
}

export const i18n: I18n = createI18n({
  legacy: false,
  globalInjection: true,
  locale: detectInitialLocale(),
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS,
  },
});

/** HMR：locale 文件改动时把新 message 合并到已存在的 i18n 实例， 无需 hard reload。 */
if (import.meta.hot) {
  import.meta.hot.accept(['./locales/zh-CN', './locales/en-US'], ([newZh, newEn]) => {
    const m = i18n.global.messages as unknown as Record<string, unknown>;
    if (newZh) m['zh-CN'] = newZh.default;
    if (newEn) m['en-US'] = newEn.default;
    // 触发 vue-i18n 内部 cache 刷新：把 locale 重置一次
    const loc = i18n.global.locale as unknown as { value: string };
    const cur = loc.value;
    loc.value = cur === 'zh-CN' ? 'en-US' : 'zh-CN';
    loc.value = cur;
  });
}

/** Element Plus 配套 locale，跟随当前 i18n locale 切换 */
export function getElementPlusLocale() {
  const loc = i18n.global.locale as unknown as { value: string };
  return loc.value === 'en-US' ? enEl : zhCnEl;
}

export function setLocale(locale: Locale) {
  const loc = i18n.global.locale as unknown as { value: string };
  loc.value = locale;
  if (typeof localStorage !== 'undefined') {
    localStorage.setItem(STORAGE_KEY, locale);
  }
  // 通知 Element Plus 切换（由使用方调用，此处仅导出辅助函数）
}

export function getLocale(): Locale {
  const loc = i18n.global.locale as unknown as { value: string };
  return loc.value as Locale;
}

export default i18n;