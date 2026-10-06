import { isProxy, toRaw } from 'vue';
import type {
  Agg, BiWidget, DashboardCanvas, DashboardLayout, DashboardTheme,
  ThemePreset, WidgetStyle, WidgetType,
} from '@/api/bi';

/**
 * 递归脱掉响应式代理。
 *
 * 为什么必须递归：`structuredClone` 走的是内部槽（internal slot），Proxy 没有，
 * Chrome 直接抛 `DataCloneError`。而 `toRaw()` **只脱一层**——它对顶层有效，
 * 嵌套的 Proxy 原封不动。
 *
 * 设计器 store 里到处是这一类写法：`widgets.value = normalizeZ([...widgets.value, created])`。
 * 展开响应式数组时，Vue 的 get 拦截器会把每个元素**重新包成 Proxy**，这个新数组
 * 又成了 ref 的 raw target —— 于是 raw 里躺着的就是 Proxy 对象本身。
 * 只调一次 toRaw 的话，`structuredClone` 仍会在组件元素上报
 * `#<Object> could not be cloned`（第一版修复就栽在这里）。
 *
 * 布局是纯 JSON 文档（最终要进 layout_json TEXT 列），不含循环引用，
 * 所以按数组 / 普通对象递归下去就够，不用维护 visited 集合。
 */
function toRawDeep<T>(v: T): T {
  const raw = isProxy(v) ? toRaw(v) : v;
  if (Array.isArray(raw)) return raw.map(toRawDeep) as unknown as T;
  if (raw && typeof raw === 'object') {
    const out: Record<string, unknown> = {};
    for (const k of Object.keys(raw as object)) {
      out[k] = toRawDeep((raw as Record<string, unknown>)[k]);
    }
    return out as T;
  }
  return raw;
}

/**
 * 深拷贝 —— 必须在 Vue 响应式对象上安全。
 *
 * 历史栈 / toLayout() / cloneWidget() 拿到的全是 Proxy，统一走这里。
 * 代价是每次保存多一次对象遍历 —— 布局最多几十个组件，可以忽略。
 */
export function deepClone<T>(v: T): T {
  return structuredClone(toRawDeep(v));
}

/** ===================== 默认画布 / 主题 ===================== */

export const DEFAULT_CANVAS: DashboardCanvas = {
  width: 1920,
  height: 1080,
  gridCols: 24,
  gridRows: 12,
  gap: 8,
  scaleMode: 'fit',
};

export const DEFAULT_THEME: DashboardTheme = { preset: 'default', dark: false };

export function defaultLayout(): DashboardLayout {
  return {
    version: 1,
    canvas: { ...DEFAULT_CANVAS },
    theme: { ...DEFAULT_THEME },
    widgets: [],
  };
}

export function emptyCanvas(width = 1920, height = 1080): DashboardCanvas {
  return { ...DEFAULT_CANVAS, width, height };
}

/**
 * 大屏主题 preset → CSS 变量表。
 *
 * AGENTS §7.3.6：`default` 预设**不硬编码 #fff**，直接引用应用 token
 * （--color-bg-elev 等），这样应用切暗色时大屏默认预设自动跟随。
 * 其余 3 套是刻意的「深色大屏」产品能力，通过 layout.theme.preset 显式选择，
 * 与应用 data-theme 正交。
 */
export const THEME_PRESETS: Record<ThemePreset, Record<string, string>> = {
  default: {
    '--bi-bg': 'var(--color-bg)',
    '--bi-panel': 'var(--color-bg-elev)',
    '--bi-panel-2': 'var(--color-bg-popover)',
    '--bi-border': 'var(--color-border)',
    '--bi-border-strong': 'var(--color-border-strong, var(--color-border))',
    '--bi-text': 'var(--color-text)',
    '--bi-text-secondary': 'var(--color-text-secondary)',
    '--bi-text-muted': 'var(--color-text-muted)',
    '--bi-accent': 'var(--color-primary)',
    '--bi-accent-soft': 'var(--color-primary-soft)',
  },
  'deep-blue': {
    '--bi-bg': '#04122b',
    '--bi-panel': '#0a1f42',
    '--bi-panel-2': '#0e2a55',
    '--bi-border': 'rgba(96, 165, 250, 0.28)',
    '--bi-border-strong': 'rgba(96, 165, 250, 0.45)',
    '--bi-text': '#e6f0ff',
    '--bi-text-secondary': '#a8c4e8',
    '--bi-text-muted': '#6b8cb8',
    '--bi-accent': '#38bdf8',
    '--bi-accent-soft': 'rgba(56, 189, 248, 0.16)',
  },
  ink: {
    '--bi-bg': '#0c0c0d',
    '--bi-panel': '#161618',
    '--bi-panel-2': '#1e1e21',
    '--bi-border': 'rgba(255, 255, 255, 0.09)',
    '--bi-border-strong': 'rgba(255, 255, 255, 0.16)',
    '--bi-text': '#f4f4f5',
    '--bi-text-secondary': '#a1a1aa',
    '--bi-text-muted': '#71717a',
    '--bi-accent': '#f59e0b',
    '--bi-accent-soft': 'rgba(245, 158, 11, 0.16)',
  },
  aurora: {
    '--bi-bg': '#0a1024',
    '--bi-panel': '#131b38',
    '--bi-panel-2': '#1b2449',
    '--bi-border': 'rgba(129, 140, 248, 0.26)',
    '--bi-border-strong': 'rgba(129, 140, 248, 0.44)',
    '--bi-text': '#eef2ff',
    '--bi-text-secondary': '#b7bff0',
    '--bi-text-muted': '#7e88bd',
    '--bi-accent': '#818cf8',
    '--bi-accent-soft': 'rgba(129, 140, 248, 0.18)',
  },
};

export function presetVars(preset: ThemePreset, accent?: string): Record<string, string> {
  const base = { ...THEME_PRESETS[preset] ?? THEME_PRESETS.default };
  if (accent && /^#[0-9a-fA-F]{3,8}$/.test(accent)) {
    base['--bi-accent'] = accent;
    base['--bi-accent-soft'] = hexToRgba(accent, 0.16);
  }
  return base;
}

function hexToRgba(hex: string, alpha: number): string {
  let h = hex.replace('#', '');
  if (h.length === 3) h = h.split('').map((c) => c + c).join('');
  const n = parseInt(h.slice(0, 6), 16);
  if (Number.isNaN(n)) return `rgba(37, 99, 235, ${alpha})`;
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

/** 把主题变量写到元素 style 上（播放页 / 设计器画布共用） */
export function applyPresetStyle(el: HTMLElement | null, theme: DashboardTheme): void {
  if (!el) return;
  const vars = presetVars(theme.preset, theme.accent);
  for (const [k, v] of Object.entries(vars)) el.style.setProperty(k, v);
  el.dataset.biTheme = theme.preset;
}

/** ===================== 组件元数据 ===================== */

export type WidgetCategory = 'metric' | 'chart' | 'content';

export interface WidgetMeta {
  type: WidgetType;
  icon: string;
  category: WidgetCategory;
  /** 默认跨格数 */
  size: { w: number; h: number };
  /** 是否必须绑定 sys_query 才能出数 */
  needsData: boolean;
  /** i18n fallback label（t(`bi.widget.${type}`, labelKey) 的第二参） */
  labelKey?: string;
}

export const WIDGET_META: Record<WidgetType, WidgetMeta> = {
  kpi:       { type: 'kpi',       icon: 'Odometer',   category: 'metric',  size: { w: 4, h: 3 },  needsData: true },
  bar:       { type: 'bar',       icon: 'Histogram',  category: 'chart',   size: { w: 8, h: 5 },  needsData: true },
  hbar:      { type: 'hbar',      icon: 'Menu',       category: 'chart',   size: { w: 8, h: 5 },  needsData: true },
  line:      { type: 'line',      icon: 'TrendCharts', category: 'chart',  size: { w: 8, h: 5 },  needsData: true },
  area:      { type: 'area',      icon: 'DataLine',   category: 'chart',   size: { w: 8, h: 5 },  needsData: true },
  pie:       { type: 'pie',       icon: 'PieChart',   category: 'chart',   size: { w: 5, h: 5 },  needsData: true },
  donut:     { type: 'donut',     icon: 'DataAnalysis', category: 'chart', size: { w: 5, h: 5 },  needsData: true },
  scatter:   { type: 'scatter',   icon: 'Scatter',    category: 'chart',   size: { w: 8, h: 5 },  needsData: true },
  radar:     { type: 'radar',     icon: 'Aim',        category: 'chart',   size: { w: 5, h: 5 },  needsData: true },
  gauge:     { type: 'gauge',     icon: 'Compass',    category: 'chart',   size: { w: 5, h: 5 },  needsData: true },
  funnel:    { type: 'funnel',    icon: 'Filter',     category: 'chart',   size: { w: 5, h: 5 },  needsData: true },
  treemap:   { type: 'treemap',   icon: 'Grid',       category: 'chart',   size: { w: 8, h: 5 },  needsData: true },
  table:     { type: 'table',     icon: 'Tickets',    category: 'content', size: { w: 10, h: 6 }, needsData: true },
  text:      { type: 'text',      icon: 'Document',   category: 'content', size: { w: 6, h: 2 },  needsData: false },
  image:     { type: 'image',     icon: 'Picture',    category: 'content', size: { w: 6, h: 4 },  needsData: false },
  marquee:   { type: 'marquee',   icon: 'Bell',       category: 'content', size: { w: 6, h: 5 },  needsData: true },
  divider:   { type: 'divider',   icon: 'Minus',      category: 'content', size: { w: 8, h: 1 },  needsData: false },
};

export const WIDGET_TYPES = Object.keys(WIDGET_META) as WidgetType[];

export const CHART_TYPES = WIDGET_TYPES.filter((t) => WIDGET_META[t].category === 'chart');

export function isChartType(t: WidgetType): boolean {
  return WIDGET_META[t]?.category === 'chart';
}

/** ===================== 组件工厂 ===================== */

let widgetSeq = 0;

export function newWidgetId(): string {
  return `w_${Date.now().toString(36)}_${(widgetSeq++).toString(36)}`;
}

export function defaultWidgetStyle(title: string): WidgetStyle {
  return {
    title,
    titleVisible: true,
    background: 'var(--bi-panel)',
    borderColor: 'var(--bi-border)',
    radius: 6,
    padding: 10,
  };
}

export function makeWidget(
  type: WidgetType,
  x: number,
  y: number,
  opts?: { w?: number; h?: number; z?: number; title?: string; style?: Partial<WidgetStyle> },
): BiWidget {
  const meta = WIDGET_META[type];
  const title = opts?.title || meta.labelKey || type;
  return {
    id: newWidgetId(),
    type,
    name: title,
    x,
    y,
    w: opts?.w ?? meta.size.w,
    h: opts?.h ?? meta.size.h,
    z: opts?.z ?? 0,
    style: { ...defaultWidgetStyle(title), ...(opts?.style || {}) },
  };
}

/** 克隆（Ctrl+D / 复制粘贴），保留配置但换新 id */
export function cloneWidget(w: BiWidget): BiWidget {
  return {
    ...deepClone(w),
    id: newWidgetId(),
    x: w.x + 1,
    y: w.y + 1,
    z: w.z + 1,
  };
}

// ===================== 栅格换算 =====================

/** 单格像素宽 / 高（扣除 gap） */
export function cellSize(canvas: DashboardCanvas): { cw: number; ch: number } {
  const cw = (canvas.width - canvas.gap * (canvas.gridCols - 1)) / canvas.gridCols;
  const ch = (canvas.height - canvas.gap * (canvas.gridRows - 1)) / canvas.gridRows;
  return { cw: Math.max(1, cw), ch: Math.max(1, ch) };
}

export function gridRectPx(
  canvas: DashboardCanvas,
  g: { x: number; y: number; w: number; h: number },
): { left: number; top: number; width: number; height: number } {
  const { cw, ch } = cellSize(canvas);
  return {
    left: g.x * (cw + canvas.gap),
    top: g.y * (ch + canvas.gap),
    width: g.w * cw + (g.w - 1) * canvas.gap,
    height: g.h * ch + (g.h - 1) * canvas.gap,
  };
}

/** 像素坐标 → 栅格（拖拽落点用，四舍五入到格） */
export function pxToGrid(
  canvas: DashboardCanvas,
  px: { left: number; top: number },
): { x: number; y: number } {
  const { cw, ch } = cellSize(canvas);
  const stepX = cw + canvas.gap;
  const stepY = ch + canvas.gap;
  return {
    x: Math.round(px.left / stepX),
    y: Math.round(px.top / stepY),
  };
}

/** 把组件夹在画布内，永远保证至少 1 格 */
export function clampWidget(w: BiWidget, canvas: DashboardCanvas): BiWidget {
  const w2 = Math.max(1, Math.min(w.w, canvas.gridCols));
  const h2 = Math.max(1, Math.min(w.h, canvas.gridRows));
  return {
    ...w,
    w: w2,
    h: h2,
    x: Math.max(0, Math.min(w.x, canvas.gridCols - w2)),
    y: Math.max(0, Math.min(w.y, canvas.gridRows - h2)),
  };
}

/** 按 id 取组件 */
export function findWidget(list: BiWidget[], id: string | null): BiWidget | null {
  if (!id) return null;
  return list.find((w) => w.id === id) || null;
}

/** 顶层 z 重排为连续序号，保证叠放顺序稳定可预测 */
export function normalizeZ(list: BiWidget[]): BiWidget[] {
  const sorted = [...list].sort((a, b) => a.z - b.z);
  return sorted.map((w, i) => (w.z === i ? w : { ...w, z: i }));
}

// ===================== 校验 / 升迁 =====================

export interface ValidateResult {
  ok: boolean;
  errors: string[];
}

/**
 * 保存前强校验。返回中文原因，直接展示给用户。
 * 只拦「必然渲染不出来」的问题，不做过度约束。
 */
export function validateLayout(layout: DashboardLayout): ValidateResult {
  const errors: string[] = [];
  if (!layout || typeof layout !== 'object') {
    return { ok: false, errors: ['布局数据为空'] };
  }
  const c = layout.canvas;
  if (!c || !Number.isFinite(c.width) || c.width <= 0 || !Number.isFinite(c.height) || c.height <= 0) {
    errors.push('画布尺寸非法');
  }
  if (!c || c.gridCols < 4 || c.gridRows < 2) {
    errors.push('栅格至少 4 列 2 行');
  }
  const widgets = Array.isArray(layout.widgets) ? layout.widgets : [];
  const ids = new Set<string>();
  widgets.forEach((w, i) => {
    const at = `组件 ${i + 1}`;
    if (!w.id || ids.has(w.id)) {
      errors.push(`${at}：id 缺失或重复`);
    }
    ids.add(w.id);
    if (!WIDGET_META[w.type]) {
      errors.push(`${at}：未知组件类型 ${w.type}`);
    }
    if (!Number.isFinite(w.x) || !Number.isFinite(w.y) || !Number.isFinite(w.w) || !Number.isFinite(w.h)) {
      errors.push(`${at}：栅格坐标非法`);
    }
    const meta = WIDGET_META[w.type];
    if (meta?.needsData && !w.data?.queryId) {
      errors.push(`${at}：${w.type} 需要绑定数据源`);
    }
  });
  return { ok: errors.length === 0, errors };
}

/**
 * schema 升迁。破坏性变更时在此补 case。
 * 当前只有 v1，向前兼容容忍（缺失字段用默认值补齐）。
 */
export function migrateLayout(raw: any): DashboardLayout {
  const base = defaultLayout();
  if (!raw || typeof raw !== 'object') return base;

  const canvas = { ...base.canvas, ...(raw.canvas || {}) };
  const theme = { ...base.theme, ...(raw.theme || {}) };
  const widgets: BiWidget[] = Array.isArray(raw.widgets) ? raw.widgets.map((w: any, i: number) => {
    const type = (WIDGET_META[w.type as WidgetType] ? w.type : 'text') as WidgetType;
    return {
      ...makeWidget(type, w.x ?? 0, w.y ?? 0, { z: i }),
      ...w,
      type,
      id: w.id || newWidgetId(),
      z: Number.isFinite(w.z) ? w.z : i,
      style: { ...defaultWidgetStyle(w.style?.title || WIDGET_META[type].labelKey || type), ...(w.style || {}) },
    };
  }) : [];

  return {
    version: Number.isFinite(raw.version) ? raw.version : 1,
    canvas,
    theme,
    widgets: normalizeZ(widgets),
  };
}

// ===================== 数据整形（聚合 / 排序 / TopN） =====================

export const AGG_LIST: Agg[] = ['none', 'sum', 'avg', 'count', 'max', 'min'];

function toNum(v: any): number {
  if (v === null || v === undefined || v === '') return 0;
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}

/**
 * 按 agg 把 x 分组的 y 列聚成一行。
 * 返回与 rows 同构的二维数组：[[groupKey, aggVal1, aggVal2...]]
 */
export function aggregate(
  rows: any[][],
  xIdx: number,
  yIdx: number[],
  agg: Agg,
): any[][] {
  if (agg === 'none' || xIdx < 0) return rows;
  const groups = new Map<string, number[]>();
  for (const r of rows) {
    const key = String(r[xIdx] ?? '');
    let acc = groups.get(key);
    if (!acc) {
      acc = new Array(yIdx.length).fill(0);
      groups.set(key, acc);
    }
    yIdx.forEach((yi, k) => {
      const v = toNum(r[yi]);
      acc![k] = agg === 'count' ? acc![k] + 1 : acc![k] + v;
    });
  }
  const out: any[][] = [];
  groups.forEach((vals, key) => {
    const row: any[] = [key];
    yIdx.forEach((_, k) => {
      const sum = vals[k];
      row.push(agg === 'avg' ? round(sum / Math.max(1, countOf(rows, xIdx, key))) : round(sum));
    });
    out.push(row);
  });
  return out;
}

function countOf(rows: any[][], xIdx: number, key: string): number {
  let n = 0;
  for (const r of rows) if (String(r[xIdx] ?? '') === key) n++;
  return n;
}

function round(n: number): number {
  return Math.round(n * 1e6) / 1e6;
}

/** 排序 + TopN（在聚合之后调用） */
export function applyTransform(
  rows: any[][],
  fieldIdx: number,
  dir: 'asc' | 'desc',
  limit?: number,
  topN?: number,
): any[][] {
  let out = [...rows];
  if (fieldIdx >= 0) {
    out.sort((a, b) => {
      const av = a[fieldIdx];
      const bv = b[fieldIdx];
      const an = Number(av);
      const bn = Number(bv);
      const numeric = Number.isFinite(an) && Number.isFinite(bn) && av !== '' && bv !== '';
      const cmp = numeric ? an - bn : String(av).localeCompare(String(bv));
      return dir === 'desc' ? -cmp : cmp;
    });
  }
  const n = topN ?? limit;
  if (n && n > 0) out = out.slice(0, n);
  return out;
}

/** KPI / 表格用：把 rows 按 mapping 取出字段索引 */
export function colIndex(columns: { key: string }[], key?: string): number {
  if (!key) return -1;
  return columns.findIndex((c) => c.key === key);
}