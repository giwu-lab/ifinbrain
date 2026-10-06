import { http } from './http';

/** 布局 schema 版本；破坏性变更时 +1 并在 utils/bi/layout.ts 补 migrate() */
export const LAYOUT_VERSION = 1;

export type WidgetType =
  // 指标
  | 'kpi'
  // 图表（ECharts）
  | 'bar' | 'hbar' | 'line' | 'area' | 'pie' | 'donut'
  | 'scatter' | 'radar' | 'gauge' | 'funnel' | 'treemap'
  // 原生
  | 'table' | 'text' | 'image' | 'marquee' | 'divider';

export type ScaleMode = 'fit' | 'fill' | 'stretch';
export type ThemePreset = 'default' | 'deep-blue' | 'ink' | 'aurora';
export type Agg = 'none' | 'sum' | 'avg' | 'count' | 'max' | 'min';

export interface DashboardCanvas {
  width: number;
  height: number;
  gridCols: number;
  gridRows: number;
  gap: number;
  scaleMode: ScaleMode;
}

export interface DashboardTheme {
  preset: ThemePreset;
  dark: boolean;
  accent?: string;
}

export interface WidgetStyle {
  title: string;
  titleVisible: boolean;
  background: string;
  borderColor: string;
  radius: number;
  padding: number;
  transparent?: boolean;
}

/** 数据源绑定：强约束指向 sys_query.id，不内嵌 SQL */
export interface WidgetDataBinding {
  queryId: number;
  params?: Record<string, string>;
  mapping: {
    x?: string;
    series?: string;
    y?: string[];
    value?: string;
    name?: string;
  };
  agg?: Agg;
  transform?: {
    sortBy?: { field: string; dir: 'asc' | 'desc' };
    limit?: number;
    topN?: number;
  };
  cacheSec?: number;
}

export interface BiWidget {
  id: string;
  type: WidgetType;
  name: string;
  /** 栅格坐标（非像素），换分辨率不塌 */
  x: number;
  y: number;
  w: number;
  h: number;
  z: number;
  locked?: boolean;
  hidden?: boolean;
  style: WidgetStyle;
  data?: WidgetDataBinding;
  /** ECharts option 片段（按 type），随 layout 序列化保存 */
  chart?: Record<string, any>;
  /** image / text 组件的额外配置 */
  extra?: Record<string, any>;
}

export interface DashboardLayout {
  version: number;
  canvas: DashboardCanvas;
  theme: DashboardTheme;
  widgets: BiWidget[];
}

// ===================== DTO =====================

export interface BiDashboardDto {
  id: number;
  code: string;
  name: string;
  description?: string;
  layout: DashboardLayout;
  theme?: DashboardTheme;
  refreshInterval: number;
  status: number;
  publishedAt?: string;
  sort: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface BiDashboardCreateBody {
  code: string;
  name: string;
  description?: string;
  layout: DashboardLayout;
  theme?: DashboardTheme;
  refreshInterval?: number;
}

export interface BiDashboardUpdateBody {
  name?: string;
  description?: string;
  layout?: DashboardLayout;
  theme?: DashboardTheme;
  refreshInterval?: number;
  sort?: number;
}

// ===================== 取数 =====================

export interface BiDataColumn {
  key: string;
  label: string;
  type: string;
}

export interface BiDataResp {
  columns: BiDataColumn[];
  rows: any[][];
  rowCount: number;
  truncated: boolean;
  durationMs: number;
  cached: boolean;
  cacheAgeMs: number;
  /** 失败时携带；成功为 null。播放页据此降级保留上一帧 */
  error?: string | null;
}

export interface BiDataQueryBody {
  queryId: number;
  params?: Record<string, string>;
  cacheSec?: number;
}

/** 数据查询选项（复用 datamgmt 的 sys_query） */
export interface BiQueryOption {
  id: number;
  name: string;
  description?: string;
  datasourceId: number;
  datasourceName?: string;
  paramsSchema?: {
    name: string;
    label: string;
    type: string;
    required?: boolean;
    defaultValue?: string;
    placeholder?: string;
  }[];
}

export const biApi = {
  list: () => http.get<any, BiDashboardDto[]>('/bi/dashboard'),
  detail: (id: number) => http.get<any, BiDashboardDto>(`/bi/dashboard/${id}`),
  create: (body: BiDashboardCreateBody) =>
    http.post<any, BiDashboardDto>('/bi/dashboard', body),
  update: (id: number, body: BiDashboardUpdateBody) =>
    http.put<any, BiDashboardDto>(`/bi/dashboard/${id}`, body),
  copy: (id: number) => http.post<any, BiDashboardDto>(`/bi/dashboard/${id}/copy`),
  remove: (id: number) => http.delete<any, null>(`/bi/dashboard/${id}`),
  publish: (id: number) => http.post<any, BiDashboardDto>(`/bi/dashboard/${id}/publish`),
  unpublish: (id: number) => http.post<any, BiDashboardDto>(`/bi/dashboard/${id}/unpublish`),

  /** 大屏取数。_silent：播放页自行在组件内展示错误，不弹全局 toast */
  queryData: (body: BiDataQueryBody) =>
    http.post<any, BiDataResp>('/bi/data/query', body, { _silent: true } as any),

  /** 可绑定的查询列表（数据绑定面板用） */
  queryOptions: () => http.get<any, BiQueryOption[]>('/bi/data/queries'),
};