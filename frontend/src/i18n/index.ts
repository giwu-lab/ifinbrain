import { createI18n } from 'vue-i18n';
import zhCN from './locales/zh-CN';
import enUS from './locales/en-US';
import zhCnEl from 'element-plus/es/locale/lang/zh-cn';
import enEl from 'element-plus/es/locale/lang/en';

const STORAGE_KEY = 'fapiao-web.lang';

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

export const i18n = createI18n({
  legacy: false,
  globalInjection: true,
  locale: detectInitialLocale(),
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS,
  },
});

/** Element Plus 配套 locale，跟随当前 i18n locale 切换 */
export function getElementPlusLocale() {
  return i18n.global.locale.value === 'en-US' ? enEl : zhCnEl;
}

export function setLocale(locale: Locale) {
  i18n.global.locale.value = locale;
  if (typeof localStorage !== 'undefined') {
    localStorage.setItem(STORAGE_KEY, locale);
  }
  // 通知 Element Plus 切换（由使用方调用，此处仅导出辅助函数）
}

export function getLocale(): Locale {
  return i18n.global.locale.value as Locale;
}

export default i18n;