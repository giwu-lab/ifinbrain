import { http } from './http';

export interface StorageConfig {
  type: 'local' | 's3';
  local: { root: string };
  s3: {
    endpoint: string;
    region: string;
    bucket: string;
    accessKey: string;
    secretKey: string;
    pathStyle: boolean;
  };
}

export interface SettingItem {
  keyName: string;
  valueText: string;
}

export interface OcrConfig {
  enabled: boolean;
  baseUrl: string;
  /** 后端返回时是占位 "********" 表示「已存有值，留空 = 不修改」 */
  apiKey: string;
  timeoutSeconds: number;
}

export const settingApi = {
  storage: () => http.get<unknown, StorageConfig>('/system/settings/storage'),
  saveStorage: (data: StorageConfig) => http.put('/system/settings/storage', data),
  ocr: () => http.get<unknown, OcrConfig>('/system/settings/ocr'),
  saveOcr: (data: OcrConfig) => http.put('/system/settings/ocr', data),
  testOcr: (data?: { baseUrl?: string }) =>
    http.post<unknown, { ok: boolean; body: unknown }>('/system/settings/ocr/test', data ?? {}),
  list: () => http.get<unknown, SettingItem[]>('/system/settings'),
  update: (data: SettingItem[]) => http.put('/system/settings', data)
};