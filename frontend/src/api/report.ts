import { http } from './http';
import type { PageResp } from '@/types';
import type { PublishDir, PublishMenuBody } from './publishMenu';

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

/**
 * 查询头控件类型。
 *
 * 注意与 `ReportParamSchema.type` 区分：那边是 **SQL 事实**（这个占位符在 SQL 里
 * 当数字用还是当日期用），这边是 **报表设计态的选择**（用文本框还是下拉框）。
 * 同一个 `status` 参数，A 报表要自由输入、B 报表要选枚举 —— schema 只有一个，改不了。
 */
export type HeaderControl = 'text' | 'number' | 'date' | 'datetime' | 'select';

/** 单个查询头字段的控件覆盖配置；整项缺失 = 跟随 schema 自带的 type */
export interface HeaderFieldCfg {
  type?: HeaderControl;
  /** type=select 时的候选值 */
  options?: string[];
  /** 控件占位提示，覆盖 schema 的 placeholder */
  placeholder?: string;
}

/** 查询头布局：显示哪些、怎么排、用什么控件 */
export interface HeaderLayout {
  /** 可见参数 name 顺序；null = 全显示按原序 */
  visible?: string[] | null;
  collapsed?: boolean;
  /** 每行字段数，默认 4 */
  columns?: number;
  /** 每个参数的控件覆盖，key = param name。缺失 = 跟随 schema */
  fields?: Record<string, HeaderFieldCfg>;
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
  /** 是否要算总行数；只有第一页需要，翻页时传 false 复用已有 total */
  withTotal?: boolean;
}

export interface ReportRunResp {
  columns: ReportColumn[];
  rows: any[][];
  offset: number;
  size: number;
  /** 总行数；-1 = 未知（后端 count 失败），前端退化成 hasMore 判断 */
  total: number;
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

/** 发布目录候选 / 请求体与报表、大屏共用，见 @/api/publishMenu */
export type { PublishDir } from './publishMenu';
export type PublishReportBody = PublishMenuBody;

export const reportApi = {
  /** 列表页：后端真分页。keyword 命中名称 / 编码 / 描述 */
  list: (params?: { page?: number; size?: number; keyword?: string }) =>
    http.get<any, PageResp<Report>>('/bi/report', { params }),
  detail: (id: number) => http.get<any, Report>(`/bi/report/${id}`),
  /** 设计器读报表：要求实体级 design 权限（能看 ≠ 能改） */
  detailForDesign: (id: number) => http.get<any, Report>(`/bi/report/${id}/design`),

  create: (body: ReportCreateBody) => http.post<any, Report>('/bi/report', body),
  update: (id: number, body: ReportUpdateBody) =>
    http.put<any, Report>(`/bi/report/${id}`, body),
  copy: (id: number) => http.post<any, Report>(`/bi/report/${id}/copy`),
  remove: (id: number) => http.delete<any, null>(`/bi/report/${id}`),

  /**
   * 发布：在菜单树里建一个入口（挂到指定目录下）。
   *
   * body 可空 —— 空 = 沿用上次的目录 / 显示名。**重新发布必须保留用户调过的配置**，
   * 否则软删保留配置就白保留了（后端只覆盖请求里真正带了的字段）。
   */
  publish: (id: number, body?: PublishReportBody) =>
    http.post<any, Report>(`/bi/report/${id}/publish`, body ?? {}),

  /** 取消发布：退回草稿 + 菜单软下线（status=0，可恢复） */
  unpublish: (id: number) => http.post<any, Report>(`/bi/report/${id}/unpublish`),

  // ===================== 实体级授权 =====================
  /** 4 个实体级权限码（view/design/publish/delete）当前绑定的角色 */
  permissions: (id: number) =>
    http.get<any, Record<string, number[]>>(`/bi/report/${id}/permissions`),
  /** 覆盖式写入授权；空数组 = 收回（admin 始终保留） */
  updatePermissions: (id: number, grants: Record<string, number[]>) =>
    http.put<any, void>(`/bi/report/${id}/permissions`, grants),

  /** 发布对话框的父目录候选 */
  publishDirs: () => http.get<any, PublishDir[]>('/bi/report/publish-dirs'),

  /**
   * 触发浏览器下载（带 Bearer）。
   *
   * 走 fetch 而不是 axios：导出是二进制 blob，axios 拿到后还得手动
   * createObjectURL + a.click()，和发票列表保持同一套写法。
   * 文件名从 Content-Disposition 的 filename* 取（中文名只靠它）。
   */
  async exportFile(id: number, kind: 'csv' | 'xlsx', params: Record<string, string | undefined> = {}) {
    const qs = new URLSearchParams();
    Object.entries(params).forEach(([k, v]) => { if (v) qs.set(k, v); });
    const token = localStorage.getItem('accessToken') || '';
    const res = await fetch(`/api/bi/report/${id}/export.${kind}?${qs.toString()}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const blob = await res.blob();
    const disp = res.headers.get('Content-Disposition') || '';
    // filename*=UTF-8''<pct-encoded> 是中文名的唯一可靠来源
    const star = /filename\*=UTF-8''([^;]+)/i.exec(disp);
    const plain = /filename="([^"]+)"/i.exec(disp);
    const filename = star
      ? decodeURIComponent(star[1].trim())
      : plain ? plain[1] : `report.${kind}`;
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
    return filename;
  },

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
