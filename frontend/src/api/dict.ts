import { http } from './http';

export interface DictType {
  id: number;
  code: string;
  name: string;
  remark?: string;
  status: 0 | 1;
  itemCount?: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface DictItem {
  id: number;
  dictId: number;
  parentId: number;
  code: string;
  label: string;
  value?: string;
  sort: number;
  status: 0 | 1;
  createdAt?: string;
  updatedAt?: string;
}

export const dictApi = {
  // 类型
  listTypes: () => http.get<unknown, DictType[]>('/system/dicts/types'),
  createType: (body: { code: string; name: string; remark?: string; status?: 0 | 1 }) =>
    http.post<unknown, DictType>('/system/dicts/types', body),
  updateType: (id: number, body: { name?: string; remark?: string; status?: 0 | 1 }) =>
    http.put<unknown, DictType>(`/system/dicts/types/${id}`, body),
  deleteType: (id: number) => http.delete(`/system/dicts/types/${id}`),

  // 条目
  listItems: (typeCode: string) =>
    http.get<unknown, DictItem[]>(`/system/dicts/types/${encodeURIComponent(typeCode)}/items`),
  createItem: (dictId: number, body: { code: string; label: string; value?: string; parentId?: number; sort?: number; status?: 0 | 1 }) =>
    http.post<unknown, DictItem>(`/system/dicts/types/${dictId}/items`, body),
  updateItem: (id: number, body: { label?: string; value?: string; parentId?: number; sort?: number; status?: 0 | 1 }) =>
    http.put<unknown, DictItem>(`/system/dicts/items/${id}`, body),
  deleteItem: (id: number) => http.delete(`/system/dicts/items/${id}`),

  /** 全量快照：Map<typeCode, DictItem[]>，供前端缓存用 */
  cache: () => http.get<unknown, Record<string, DictItem[]>>('/system/dicts/cache'),

  /** 导出全库字典为 JSON 字符串（前端再下载） */
  exportUrl: () => '/api/system/dicts/export',

  /**
   * 批量导入：上传 JSON / CSV 文件
   * dryRun=true 只解析不写库；false 时真正 upsert
   */
  importFile: (file: File, dryRun: boolean) => {
    const fd = new FormData();
    fd.append('file', file);
    const qs = dryRun ? '?dryRun=true' : '';
    return fetch(`/api/system/dicts/import${qs}`, {
      method: 'POST',
      body: fd,
      headers: {
        Authorization: `Bearer ${localStorage.getItem('accessToken') || ''}`,
      },
    }).then(async (r) => {
      const j = await r.json();
      if (j.code !== 0) throw new Error(j.message || `HTTP ${r.status}`);
      return j.data as {
        dryRun: boolean;
        ok: number;
        updated: number;
        skipped: number;
        error: number;
        messages: string[];
      };
    });
  },
};