import { http } from './http';
import type { PageResp } from '@/types';
import type { PublishDir, PublishMenuBody } from './publishMenu';

/** 布局 schema 版本；破坏性变更时 +1 并在 utils/bi/layout.ts 补 migrate() */
export const LAYOUT_VERSION = 1;

export type WidgetType =
  // 指标
  | 'kpi'
  // 图表（ECharts）
  | 'bar' | 'hbar' | 'line' | 'area' | 'pie' | 'donut'
  | 'scatter' | 'radar' | 'gauge' | 'funnel' | 'treemap'
  | 'combo' | 'stack'
  // 原生
  | 'table' | 'text' | 'image' | 'marquee' | 'divider'
  // 装饰（不取数、纯视觉，借鉴 DataV 大屏常见件）
  | 'border' | 'titlebar';

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

/**
 * 联动规则：源组件被点击时，把点击到的分类值写进本组件的某个查询参数。
 *
 * 方向是单向的（源 → 目标）。没有做「全局筛选器」或 ECharts dataset 级联，
 * 因为那要求所有组件共享一份筛选状态，和「每个组件各自绑定 query + params」
 * 的现有模型冲突。先覆盖「点柱子联动明细表」这类真实需求。
 */
export interface WidgetLink {
  /** 触发源组件 id */
  from: string;
  /** 写入本组件的哪个查询参数（对应 sys_query.params_schema[].name） */
  param: string;
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
  /** image / text / 装饰类组件的额外配置 */
  extra?: Record<string, any>;
  /** 联动规则（设计态，保存进 layout；点击产生的值不进这里，见 useBiInteraction） */
  interact?: { on?: WidgetLink[] };
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
  /** 列表页：后端真分页。keyword 命中名称 / 编码 / 描述 */
  list: (params?: { page?: number; size?: number; keyword?: string }) =>
    http.get<any, PageResp<BiDashboardDto>>('/bi/dashboard', { params }),

  /**
   * 已发布大屏全量列表（展示墙轮播用）。
   * 轮播要把所有已发布大屏排一遍序再挑，不适用分页 —— 后端 /published 已按 sort 排好。
   */
  published: () => http.get<any, BiDashboardDto[]>('/bi/dashboard/published'),

  detail: (id: number) => http.get<any, BiDashboardDto>(`/bi/dashboard/${id}`),
  /** 设计器读大屏：要求实体级 design 权限（能看 ≠ 能改） */
  detailForDesign: (id: number) => http.get<any, BiDashboardDto>(`/bi/dashboard/${id}/design`),
  create: (body: BiDashboardCreateBody) =>
    http.post<any, BiDashboardDto>('/bi/dashboard', body),
  update: (id: number, body: BiDashboardUpdateBody) =>
    http.put<any, BiDashboardDto>(`/bi/dashboard/${id}`, body),
  copy: (id: number) => http.post<any, BiDashboardDto>(`/bi/dashboard/${id}/copy`),
  remove: (id: number) => http.delete<any, null>(`/bi/dashboard/${id}`),
  /**
   * 发布：翻 status 位（进轮播）+ 在菜单树里建入口（挂到指定目录下）。
   *
   * body 可空 —— 空 = 沿用上次的目录 / 显示名。**重新发布必须保留用户调过的配置**，
   * 否则软删保留配置就白保留了（后端只覆盖请求里真正带了的字段）。
   */
  publish: (id: number, body?: PublishMenuBody) =>
    http.post<any, BiDashboardDto>(`/bi/dashboard/${id}/publish`, body ?? {}),

  /** 取消发布：退回草稿（退出轮播）+ 菜单软下线（status=0，可恢复） */
  unpublish: (id: number) => http.post<any, BiDashboardDto>(`/bi/dashboard/${id}/unpublish`),

  // ===================== 实体级授权 =====================
  /** 4 个实体级权限码（view/design/publish/delete）当前绑定的角色 */
  permissions: (id: number) =>
    http.get<any, Record<string, number[]>>(`/bi/dashboard/${id}/permissions`),
  /** 覆盖式写入授权；空数组 = 收回（admin 始终保留） */
  updatePermissions: (id: number, grants: Record<string, number[]>) =>
    http.put<any, void>(`/bi/dashboard/${id}/permissions`, grants),

  /** 发布对话框的父目录候选 */
  publishDirs: () => http.get<any, PublishDir[]>('/bi/dashboard/publish-dirs'),

  /** 大屏取数。_silent：播放页自行在组件内展示错误，不弹全局 toast */
  queryData: (body: BiDataQueryBody) =>
    http.post<any, BiDataResp>('/bi/data/query', body, { _silent: true } as any),

  /** 可绑定的查询列表（数据绑定面板用） */
  queryOptions: () => http.get<any, BiQueryOption[]>('/bi/data/queries'),
};