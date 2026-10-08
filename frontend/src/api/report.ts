import { http } from './http';
import type { PageResp } from '@/types';

/** 查询参数定义 —— 直接对应后端 QueryParamSchema */
export interface ReportParamSchema {
  name: string;
  label: string;
  /** string | number | date | datetime */
  type: string;
  required?: boolean;
  defaultValue?: string;
  placeholder?: string;
}

/** 查询头布局：只管「显示哪些、怎么排」，字段定义运行时从 query 读 */
export interface HeaderLayout {
  /** 可见参数 name 顺序；null = 全显示按原序 */
  visible?: string[] | null;
  collapsed?: boolean;
  /** 每行字段数，默认 4 */
  columns?: number;
}

export interface ColumnCfg {
  key: string;
  label?: string;
  width?: number;
  align?: 'left' | 'center' | 'right';
  fixed?: boolean;
  visible?: boolean;
}

export interface Report {
  id: number;
  code: string;
  name: string;
  description?: string;
  queryId: number;
  queryName?: string;
  header: HeaderLayout;
  columns: ColumnCfg[];
  defaultParams: Record<string, string>;
  pageSize: number;
  maxRows: number;
  status: number;
  createdBy?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface ReportCreateBody {
  code: string;
  name: string;
  description?: string;
  queryId: number;
  header?: HeaderLayout;
  columns?: ColumnCfg[];
  defaultParams?: Record<string, string>;
  pageSize?: number;
  maxRows?: number;
}

export type ReportUpdateBody = Partial<Omit<ReportCreateBody, 'code'>>;

/** 列元数据，与 QueryResultDto.Column 同构 */
export interface ReportColumn {
  key: string;
  label: string;
  type: string;
  width?: number | null;
  align?: string | null;
}

export interface ReportRunBody {
  params?: Record<string, string>;
  offset: number;
  size?: number;
}

export interface ReportRunResp {
  columns: ReportColumn[];
  rows: any[][];
  offset: number;
  size: number;
  hasMore: boolean;
  capped: boolean;
  nextOffset: number;
  durationMs: number;
  timeoutSec: number;
}

/** 设计器可绑定的查询选项（不含 SQL 文本） */
export interface ReportQueryOption {
  id: number;
  name: string;
  description?: string;
  params: ReportParamSchema[];
}

export const reportApi = {
  /** 列表页：后端真分页。keyword 命中名称 / 编码 / 描述 */
  list: (params?: { page?: number; size?: number; keyword?: string }) =>
    http.get<any, PageResp<Report>>('/bi/report', { params }),
  detail: (id: number) => http.get<any, Report>(`/bi/report/${id}`),

  create: (body: ReportCreateBody) => http.post<any, Report>('/bi/report', body),
  update: (id: number, body: ReportUpdateBody) =>
    http.put<any, Report>(`/bi/report/${id}`, body),
  copy: (id: number) => http.post<any, Report>(`/bi/report/${id}/copy`),
  remove: (id: number) => http.delete<any, null>(`/bi/report/${id}`),
  publish: (id: number) => http.post<any, Report>(`/bi/report/${id}/publish`),
  unpublish: (id: number) => http.post<any, Report>(`/bi/report/${id}/unpublish`),

  /** 虚拟滚动分页取数。_silent：报表页自己在表头显示错误，不弹全局 toast 轰炸 */
  run: (id: number, body: ReportRunBody) =>
    http.post<any, ReportRunResp>(`/bi/report/${id}/run`, body, { _silent: true } as any),

  /**
   * 设计器「试跑」：按 queryId 取首屏看列，**不落库**。
   *
   * 用 queryId 而不是报表 id：新建报表还没有 id，先看数据再决定要不要存。
   * _silent 与 run 同理 —— 失败由设计器的 previewErr 就地显示，不弹全局 toast。
   */
  preview: (queryId: number, params?: Record<string, string>, size = 50) =>
    http.post<any, ReportRunResp>(
      '/bi/report/preview',
      { queryId, params: params ?? {}, size },
      { _silent: true } as any
    ),

  /** 可绑定的查询列表（设计器用） */
  queryOptions: () => http.get<any, ReportQueryOption[]>('/bi/report/query-options'),
};
