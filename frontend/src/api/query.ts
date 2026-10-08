import { http } from './http';
import type { PageResp } from '@/types';

export type ParamType = 'string' | 'text' | 'number' | 'date' | 'datetime';

export interface QueryParamSchema {
  name: string;
  label?: string;
  type?: ParamType;
  required?: boolean;
  defaultValue?: string;
  placeholder?: string;
}

export interface QueryItem {
  id: number;
  name: string;
  description?: string;
  datasourceId: number;
  datasourceName: string;
  dbType: string;
  sqlTemplate: string;
  paramsSchema: QueryParamSchema[];
  status: number;
  lastRunOk: number | null;
  lastRunAt: string | null;
  lastRunRowCount: number | null;
  lastRunDurationMs: number | null;
  lastRunMsg: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  /** 已发布的查询页面 menu id（null = 未发布） */
  publishedMenuId?: number | null;
  publishedMenuName?: string | null;
  publishedMenuPath?: string | null;
  /** 0 = 已取消（status=0），1 = 已发布（status=1） */
  publishedMenuStatus?: number | null;
  publishedMenuIcon?: string | null;
  publishedMenuSort?: number;
}

export interface QueryColumn {
  key: string;
  label: string;
  type: 'string' | 'number' | 'date' | 'datetime' | 'boolean' | 'unknown';
}

export interface QueryResult {
  columns: QueryColumn[];
  rows: any[][];
  rowCount: number;
  truncated: boolean;
  durationMs: number;
  rowLimit: number;
  timeoutSec: number;
}

export const queryApi = {
  /** 列表页：后端真分页。keyword 命中名称 / 描述 */
  list: (params?: { page?: number; size?: number; keyword?: string }) =>
    http.get<any, PageResp<QueryItem>>('/datamgmt/queries', { params }),
  detail: (id: number) => http.get<any, QueryItem>(`/datamgmt/queries/${id}`),
  create: (body: Partial<QueryItem>) => http.post<any, QueryItem>('/datamgmt/queries', body),
  update: (id: number, body: Partial<QueryItem>) => http.put<any, QueryItem>(`/datamgmt/queries/${id}`, body),
  remove: (id: number) => http.delete<any, null>(`/datamgmt/queries/${id}`),
  run: (id: number, paramValues: Record<string, string> = {}) =>
    http.post<any, QueryResult>(`/datamgmt/queries/${id}/run`, { paramValues }),
};