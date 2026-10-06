import { defineStore } from 'pinia';

/**
 * 历史：曾用于从 sys_i18n_message 拉取运行时翻译覆盖、合并到 vue-i18n。
 * 现已统一收口到前端 TS 文件（zh-CN.ts / en-US.ts），本 store 保留为占位
 * —— 若有旧的 `useI18nStore()` 调用不会因 import 失败而炸。
 * 后续如有需要可整体删除。
 */
export const useI18nStore = defineStore('i18n', () => {
  return {};
});