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

export interface ParserConfig {
  enabled: boolean;
  baseUrl: string;
  /** 后端返回时是占位 "********" 表示「已存有值，留空 = 不修改」 */
  apiKey: string;
  timeoutSeconds: number;
  /** 单文件解析路径（默认 /extract） */
  extractPath: string;
  /** 批量解析路径（默认 /extract/batch） */
  batchPath: string;
  /** 健康检查路径（默认 /health） */
  healthPath: string;
}

export const settingApi = {
  storage: () => http.get<unknown, StorageConfig>('/system/settings/storage'),
  saveStorage: (data: StorageConfig) => http.put('/system/settings/storage', data),
  parser: () => http.get<unknown, ParserConfig>('/system/settings/parser'),
  saveParser: (data: ParserConfig) => http.put('/system/settings/parser', data),
  testParser: (data?: { baseUrl?: string }) =>
    http.post<unknown, { ok: boolean; body: unknown; url: string }>('/system/settings/parser/test', data ?? {}),
  list: () => http.get<unknown, SettingItem[]>('/system/settings'),
  update: (data: SettingItem[]) => http.put('/system/settings', data)
};