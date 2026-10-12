import type {
  ColumnCfg,
  HeaderControl,
  HeaderFieldCfg,
  HeaderLayout,
  ReportColumn,
  ReportParamSchema,
} from '@/api/report';

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
 * 可选控件类型（设计器下拉框的选项来源）。
 *
 * 与 AGENTS 早先的约定一致：**不擅自扩展** schema 的 string/number/date/datetime，
 * 只额外加一个 select —— 它不引入新的值域（还是字符串），只是把候选值列出来，
 * 后端的参数类型转换逻辑一行都不用动。
 */
export const HEADER_CONTROLS: HeaderControl[] = ['text', 'number', 'date', 'datetime', 'select'];

/** schema 的 type → 控件类型。schema 没声明或声明了未知值一律退化成文本框。 */
function schemaToControl(type?: string): HeaderControl {
  switch ((type || '').toLowerCase()) {
    case 'number':
      return 'number';
    case 'date':
      return 'date';
    case 'datetime':
      return 'datetime';
    case 'text':
      return 'text';
    default:
      // schema 里是 'string'（历史命名）或空 —— 都是文本框
      return 'text';
  }
}

/**
 * 某个参数最终用哪个控件渲染。
 *
 * 优先级：**报表的显式配置 > 查询 schema 的 type > 文本框**。
 *
 * 老报表的 header.fields 是空的，全部走 schema 分支，渲染结果与加这个功能之前
 * 完全一致 —— 这就是它不需要数据迁移的原因。
 *
 * 设计器与播放页共用这一个函数：设计时下拉看到「文本框」，播放页就一定是文本框，
 * 不会出现两处各推导一次、结果却不一致的偏差。
 */
export function controlOf(
  p: ReportParamSchema,
  fields?: Record<string, HeaderFieldCfg> | null
): HeaderControl {
  const t = fields?.[p.name]?.type;
  if (t && HEADER_CONTROLS.includes(t)) return t;
  return schemaToControl(p.type);
}

/** 该参数的控件占位提示：报表配置优先于 schema */
export function placeholderOf(
  p: ReportParamSchema,
  fields?: Record<string, HeaderFieldCfg> | null
): string | undefined {
  return fields?.[p.name]?.placeholder || p.placeholder;
}

/** 该参数的下拉候选值（仅 type=select 有意义） */
export function optionsOf(
  p: ReportParamSchema,
  fields?: Record<string, HeaderFieldCfg> | null
): string[] {
  return fields?.[p.name]?.options ?? [];
}

/**
 * 按 header 布局决定查询头显示哪些字段、按什么顺序。
 *
 * `visible` 为 null/空 → 全部可见，保持 schema 原序。
 * `visible` 非空 → **严格按它来**：设计器里取消勾选的字段就是不该出现在查询头里。
 * 早先这里会在末尾把 schema 里漏掉的字段补回来（容忍"配置过期"），结果设计器
 * 的勾选框是个摆设 —— 取消勾选后播放页照样渲染。现在 `visible` 是权威值，
 * 想全显示就存 null，想自定义就存全量。
 *
 * 注意：被隐藏的字段**照常带值**（values 里仍有它的值，sanitizeParams 的白名单
 * 按 schema 走而不是按 visible），所以"勾掉一个必填参数"不会把查询打挂。
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
