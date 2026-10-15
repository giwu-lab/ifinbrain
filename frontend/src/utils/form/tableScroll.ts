/**
 * 表格「列太多时要不要常驻横向滚动条」的判定规则。
 *
 * 为什么要单独一层：element-plus 的 el-table 滚动条默认**鼠标悬停才出现**
 * （el-scrollbar 的 visible 只在 mousemove 时置 true），列一多，表格被容器右边缘
 * 裁掉却没有滚动条，用户既看不出来还有列、也不知道能横向滚。
 *
 * 而 `scrollbar-always-on` 是静态开关 —— 无条件打开的话，不溢出的表格也会多出
 * 一条竖向轨道。所以要先算「这批列最少要占多宽」，再跟容器实际宽度比。
 */

/** 列定义里我们只关心宽度，其余（标题/必填/选项）不影响占位 */
export interface TableWidthColumn {
  width?: number | null;
}

export interface TableMinWidthInput {
  columns: readonly TableWidthColumn[];
  /** 左侧序号列宽；没有这列就传 0（展示模式） */
  indexColWidth?: number;
  /** 右侧操作列宽；只读或没有操作列时传 0 */
  actionsColWidth?: number;
  /** 列没设宽度时的最小宽度，需与组件里 el-table-column 的 min-width 保持一致 */
  defaultColWidth?: number;
}

export const DEFAULT_COL_WIDTH = 140;
export const INDEX_COL_WIDTH = 48;
export const ACTIONS_COL_WIDTH = 104;

/** 边框与滚动条自身占掉的几像素：差一像素就判「要滚动」纯属视觉噪声 */
const OVERFLOW_EPSILON = 4;

/**
 * 这批列最少要占多宽。
 *
 * 规则与 el-table 的 flex 布局一致：设了 width 就按 width 算，没设的按 min-width 算。
 * 单元格控件（el-input 等）有可能比 min-width 更宽，但那是内容撑出来的，
 * 判定「要不要常驻滚动条」用最小宽度就够了 —— 真溢出了 el-table 也会自己滚。
 */
export function tableMinWidth(input: TableMinWidthInput): number {
  const def = input.defaultColWidth ?? DEFAULT_COL_WIDTH;
  // 列定义来自 schema 的 JSON，数组与元素都可能缺 —— 一个 null 不该让整块表格崩掉
  const cols = (input.columns ?? []).reduce((sum, c) => {
    const w = typeof c?.width === 'number' && c.width > 0 ? c.width : def;
    return sum + w;
  }, 0);
  return cols + (input.indexColWidth ?? 0) + (input.actionsColWidth ?? 0);
}

/**
 * 是否需要横向滚动。
 *
 * @param minWidth    列最少要占的宽度（{@link tableMinWidth}）
 * @param containerWidth 容器实际宽度；传 0 表示「还没量到」，按需要滚动处理 ——
 *                       首帧就亮出滚动条，好过量到之后再闪一下消失
 */
export function needsScrollX(minWidth: number, containerWidth: number): boolean {
  if (containerWidth <= 0) return true;
  return minWidth > containerWidth + OVERFLOW_EPSILON;
}
