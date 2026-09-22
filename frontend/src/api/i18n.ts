import { http } from './http';

export interface I18nMessage {
  id: number;
  namespace: string;
  code: string;
  valueJson: string;
  updatedAt: string;
}

export interface I18nImportResult {
  inserted: number;
  updated: number;
  skipped: number;
  errors: string[] | null;
}

export const i18nApi = {
  /** 全量快照：{ locale: nestedMap } — 给前端一次性注入 vue-i18n messages */
  messagesAll: () =>
    http.get<unknown, Record<string, Record<string, any>>>('/system/i18n/messages/all'),

  /** 单个命名空间下所有条目 */
  listByNamespace: (namespace: string) =>
    http.get<unknown, I18nMessage[]>(`/system/i18n/${encodeURIComponent(namespace)}`),

  namespaces: () => http.get<unknown, string[]>('/system/i18n/namespaces'),

  counts: () => http.get<unknown, Record<string, number>>('/system/i18n/counts'),

  upsert: (namespace: string, code: string, valueJson: string) =>
    http.put<unknown, I18nMessage>(`/system/i18n/${encodeURIComponent(namespace)}/${encodeURIComponent(code)}`, {
      namespace,
      code,
      valueJson,
    }),

  delete: (namespace: string, code: string) =>
    http.delete(`/system/i18n/${encodeURIComponent(namespace)}/${encodeURIComponent(code)}`),

  resetNamespace: (namespace: string) =>
    http.delete(`/system/i18n/${encodeURIComponent(namespace)}/reset`),

  exportUrl: () => '/api/system/i18n/export',

  importFile: (file: File, mode: 'merge' | 'replace' = 'merge') => {
    const fd = new FormData();
    fd.append('file', file);
    return fetch(`/api/system/i18n/import?mode=${mode}`, {
      method: 'POST',
      body: fd,
      headers: {
        Authorization: `Bearer ${localStorage.getItem('accessToken') || ''}`,
      },
    }).then(async (r) => {
      const j = await r.json();
      if (j.code !== 0) throw new Error(j.message || `HTTP ${r.status}`);
      return j.data as I18nImportResult;
    });
  },

  /** 一键同步：把 classpath:i18n/defaults.json 写入 DB（merge 模式，已存在则覆盖为默认值） */
  syncDefaults: () =>
    http.post<unknown, I18nImportResult>('/system/i18n/sync-defaults'),
};