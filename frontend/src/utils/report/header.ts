import type { ColumnCfg, HeaderLayout, ReportColumn, ReportParamSchema } from '@/api/report';

/**
 * 查询头渲染与列合并的纯函数。
 *
 * 全部无副作用 —— 设计器（预览）与播放页（真实查询）共用同一套推导逻辑，
 * 避免「设计时好看、跑起来不一样」这种最难查的偏差。
 */

/** 有效列宽下限/上限。低于 80 看不清表头，高于 600 横向滚动会难用。 */
const MIN_W = 80;
const MAX_W = 600;
const DEFAULT_W = 140;

/**
 * 按 header 布局决定查询头显示哪些字段、按什么顺序。
 *
 * `visible` 为 null/空 → 全部可见，保持 schema 原序。
 * `visible` 里出现 schema 没有的 name → 忽略（配置过期不该让查询头崩）。
 */
export function visibleParams(
  schema: ReportParamSchema[],
  header?: HeaderLayout | null
): ReportParamSchema[] {
  const all = schema ?? [];
  const list = header?.visible;
  if (!list || !list.length) return all;

  const byName = new Map(all.map((p) => [p.name, p]));
  const out: ReportParamSchema[] = [];
  const seen = new Set<string>();
  for (const name of list) {
    const p = byName.get(name);
    if (p && !seen.has(name)) {
      out.push(p);
      seen.add(name);
    }
  }
  // schema 里有、visible 里漏掉的 → 追加到末尾，避免新加的参数在查询头里凭空消失
  for (const p of all) if (!seen.has(p.name)) out.push(p);
  return out;
}

/** 查询头初始值：schema 默认值 + 报表配置的 defaultParams（后者优先） */
export function initialParams(
  schema: ReportParamSchema[],
  defaults?: Record<string, string> | null
): Record<string, string> {
  const out: Record<string, string> = {};
  for (const p of schema ?? []) out[p.name] = p.defaultValue ?? '';
  for (const [k, v] of Object.entries(defaults ?? {})) out[k] = v ?? '';
  return out;
}

/**
 * 必填校验。返回第一个不满足的字段名（没有则 null）。
 *
 * 只查 `required` 且值为空白的字段 —— 后端 `requireNonBlank` 用的是同一判据，
 * 前端先拦一道能让用户在表单里就看到红字，而不是等一个 400。
 */
export function firstMissingRequired(
  params: ReportParamSchema[],
  values: Record<string, string>
): string | null {
  for (const p of params ?? []) {
    if (!p.required) continue;
    const v = values[p.name];
    if (v === undefined || v === null || String(v).trim() === '') return p.name;
  }
  return null;
}

/**
 * 实际渲染用的列定义 = 查询结果列 ∩ 报表列配置。
 *
 * 两条设计约束：
 *  1. 以**结果列**为准 —— SQL 改了 SELECT 列表，报表多一列少一列都能自适应，
 *     而不是拿配置里的 key 去 find 找不到就渲染空白列。
 *  2. 找不到配置的列给默认值 —— 新加的列不该隐身。
 */
export function mergeColumns(
  resultColumns: ReportColumn[],
  cfg: ColumnCfg[] | null | undefined
): ColumnCfg[] {
  const cols = resultColumns ?? [];
  const byKey = new Map((cfg ?? []).map((c) => [c.key, c]));

  return cols
    .map((rc) => {
      const c = byKey.get(rc.key);
      if (c?.visible === false) return null;      // 显式隐藏
      const width = clampWidth(c?.width ?? rc.width ?? DEFAULT_W);
      return {
        key: rc.key,
        label: c?.label || rc.label || rc.key,
        width,
        align: normalizeAlign(c?.align ?? rc.align, rc.type),
        fixed: c?.fixed ?? false,
        visible: true,
      } as ColumnCfg;
    })
    .filter((c): c is ColumnCfg => c !== null);
}

/** 列宽夹紧；非有限数（NaN / undefined）一律回默认宽 */
export function clampWidth(w: number | null | undefined): number {
  if (typeof w !== 'number' || !Number.isFinite(w)) return DEFAULT_W;
  return Math.max(MIN_W, Math.min(Math.round(w), MAX_W));
}

/** 数字列默认右对齐 —— 与 AGENTS §8.3 表格标准一致 */
export function normalizeAlign(a: string | null | undefined, type?: string): 'left' | 'center' | 'right' {
  if (a === 'left' || a === 'center' || a === 'right') return a;
  return type === 'number' ? 'right' : 'left';
}

/**
 * 单元格文本。
 *
 * null/undefined → 空串（不是 "null"）—— SQL 的 NULL 应该在表格里表现为空白，
 * 显示成字面量 "null" 会让人以为查错了。
 */
export function cellText(v: unknown): string {
  if (v === null || v === undefined) return '';
  if (typeof v === 'object') {
    try {
      return JSON.stringify(v);
    } catch {
      return String(v);
    }
  }
  return String(v);
}
