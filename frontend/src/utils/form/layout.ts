/**
 * 表单栅格布局模型。
 *
 * ---------------------------------------------------------------------------
 * 为什么不用 `utils/bi/layout.ts`（而是自己写一套）
 * ---------------------------------------------------------------------------
 * BI 大屏是**定尺**画布：1920×1080、gridRows 固定、`clampWidget` 会把 y 夹在
 * `gridRows - h` 以内，组件按绝对像素定位。
 *
 * 表单恰恰相反：高度**无界、纵向滚动**，还要在 deviceMode（1200 / 768 / 375）
 * 下保持可读。套用 BI 那套会得到两个必然的错误：y 被夹死在固定行数内、
 * 绝对定位让移动端直接失效。
 *
 * 因此这里用另一套更适合表单的模型：
 *   - 24 列栅格（与项目已有的 el-row/el-col :span 口径一致）
 *   - y 无上界，按需向下延伸
 *   - 渲染用 **百分比 + CSS Grid**，天然响应式，不需要像 BI 那样做缩放适配
 *
 * 另外 BI 布局工具当前正被另一条工作线改动，这里不复用可以避免耦合。
 */

import type { FormFieldSchema } from '@/api/formDesign';

/** 栅格列数 —— 与 el-form 的 24 栅格同口径 */
export const GRID_COLS = 24;

/** 单行高度（px）。字段的 layout.h 为几就占几行 */
export const ROW_HEIGHT = 64;

export interface FormGridLayout {
  /** 起始列，0-based */
  x: number;
  /** 起始行，0-based，无上界 */
  y: number;
  /** 跨列数，1..24 */
  w: number;
  /** 跨行数，≥1 */
  h: number;
}

/** 整份表单的栅格配置（存到表单级，不存到字段级） */
export interface FormGridConfig {
  cols: number;
  rowHeight: number;
  gap: number;
}

export const DEFAULT_GRID: FormGridConfig = {
  cols: GRID_COLS,
  rowHeight: ROW_HEIGHT,
  gap: 16,
};

// ===================== 基础工具 =====================

export function clampLayout(
  l: Partial<FormGridLayout> | undefined,
  cfg: FormGridConfig = DEFAULT_GRID,
): FormGridLayout {
  const w = Math.max(1, Math.min(Math.round(l?.w ?? 24), cfg.cols));
  const h = Math.max(1, Math.round(l?.h ?? 1));
  return {
    w,
    h,
    x: Math.max(0, Math.min(Math.round(l?.x ?? 0), cfg.cols - w)),
    // y 不设上界 —— 表单高度随字段数增长
    y: Math.max(0, Math.round(l?.y ?? 0)),
  };
}

/** 两个矩形是否重叠 */
export function overlaps(a: FormGridLayout, b: FormGridLayout): boolean {
  return a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y;
}

/**
 * 给定尺寸找一个不与现有矩形重叠的落点。
 *
 * 从左上角起逐行扫描，找第一块放得下的空位 —— 保证新字段总是出现在
 * 「最靠前的空位」而不是被挤到画布末尾。
 */
export function findFreeSlot(
  existing: FormGridLayout[],
  w: number,
  h: number,
  cfg: FormGridConfig = DEFAULT_GRID,
): FormGridLayout {
  const width = Math.max(1, Math.min(w, cfg.cols));
  const height = Math.max(1, h);
  const maxY = existing.reduce((m, e) => Math.max(m, e.y + e.h), 0);

  for (let y = 0; y <= maxY; y++) {
    for (let x = 0; x + width <= cfg.cols; x++) {
      const candidate: FormGridLayout = { x, y, w: width, h: height };
      if (!existing.some((e) => overlaps(candidate, e))) return candidate;
    }
  }
  // 理论上到不了这里（下方永远是空的），保底放在末尾
  return { x: 0, y: maxY, w: width, h: height };
}

/** 整份表单占了几行 */
export function layoutRows(fields: FormFieldSchema[], cfg: FormGridConfig = DEFAULT_GRID): number {
  return fields.reduce(
    (m, f) => (f.layout ? Math.max(m, f.layout.y + f.layout.h) : m),
    0,
  );
}

// ===================== 迁移 =====================

/**
 * 老 schema → 栅格布局。
 *
 * 老模型只有一个 `fullWidth` 布尔：true 独占整行，false 与下一个字段并排。
 * 这里按同样语义铺一遍，之后**渲染路径只剩一条**（永远读 layout），
 * 不再需要「有 layout 走新逻辑、没 layout 走老逻辑」的双分支。
 *
 * DIVIDER 一律整行 —— 半宽的分割线没有意义。
 * TABLE 同理：多列表格挤在半行没法看（这条与 FormRenderer 原有约束一致）。
 */
export function migrateFields(
  fields: FormFieldSchema[],
  cfg: FormGridConfig = DEFAULT_GRID,
): FormFieldSchema[] {
  const taken: FormGridLayout[] = [];
  const out: FormFieldSchema[] = [];
  let cursorX = 0;
  let cursorY = 0;

  for (const f of fields) {
    if (f.layout) {
      // 已有栅格坐标：照用，但夹回合法范围
      const layout = clampLayout(f.layout, cfg);
      taken.push(layout);
      cursorY = Math.max(cursorY, layout.y + layout.h);
      cursorX = 0;
      out.push({ ...f, layout });
      continue;
    }

    const forceFull = f.type === 'DIVIDER' || f.type === 'TABLE' || f.fullWidth !== false;
    let layout: FormGridLayout;

    if (forceFull) {
      // 关键：当前行右侧还有半宽字段没排完的话，必须先换行。
      // 否则满宽元素会从 x=0 开始铺，与左边那个半宽字段**重叠**。
      if (cursorX > 0) {
        cursorY += 1;
        cursorX = 0;
      }
      layout = { x: 0, y: cursorY, w: cfg.cols, h: 1 };
      cursorY += 1;
      cursorX = 0;
    } else {
      // 半宽：当前行右侧还有位置就并排，否则换行
      const half = Math.floor(cfg.cols / 2);
      if (cursorX + half > cfg.cols) {
        cursorY += 1;
        cursorX = 0;
      }
      layout = { x: cursorX, y: cursorY, w: half, h: 1 };
      cursorX += half;
    }

    taken.push(layout);
    out.push({ ...f, layout });
  }

  return out;
}

// ===================== CSS Grid 换算 =====================

/**
 * 栅格 → CSS Grid 的 grid-column / grid-row。
 *
 * 用 CSS Grid 而非绝对定位，是为了让表单在 deviceMode 切换和窗口缩放时
 * 自动重排 —— 百分比 + 栅格天然响应式，不用写任何适配代码。
 */
export function toGridPlacement(
  l: FormGridLayout,
  cfg: FormGridConfig = DEFAULT_GRID,
): Record<string, string> {
  const y = Math.max(0, Math.round(l.y));
  return {
    gridColumn: `${l.x + 1} / span ${l.w}`,
    // y=0 的字段落在第 1 行；栅格行高由 .form-grid 的 grid-auto-rows 控制
    gridRow: `${y + 1} / span ${l.h}`,
    minWidth: '0',
  };
}

/** 布局是否与另一个字段重叠（保存前校验用） */
export function hasCollision(
  fields: FormFieldSchema[],
  movingId: string,
  moving: FormGridLayout,
): FormFieldSchema | null {
  return (
    fields.find((f) => f.id !== movingId && f.layout && overlaps(f.layout, moving)) ?? null
  );
}

/** 落点合法性：是否越界 / 是否与他人重叠 */
export function validateLayoutOf(
  fields: FormFieldSchema[],
  id: string,
  layout: FormGridLayout,
  cfg: FormGridConfig = DEFAULT_GRID,
): { ok: boolean; reason?: string } {
  const hit = hasCollision(fields, id, layout);
  if (hit) {
    return { ok: false, reason: `与字段「${hit.label || hit.key}」重叠` };
  }
  if (layout.x < 0 || layout.x + layout.w > cfg.cols) {
    return { ok: false, reason: `超出 ${cfg.cols} 列画布` };
  }
  if (layout.y < 0) {
    return { ok: false, reason: '行号不能为负' };
  }
  return { ok: true };
}