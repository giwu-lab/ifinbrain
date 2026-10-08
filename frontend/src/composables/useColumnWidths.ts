import { ref, watch } from 'vue';

const STORAGE_KEY = 'ifinbrain.column-widths.v1';

type TableWidths = Record<string, Record<string, number>>;

function loadAll(): TableWidths {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as TableWidths) : {};
  } catch { return {}; }
}
function saveAll(v: TableWidths) {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(v)); } catch { /* ignore */ }
}

/**
 * 列宽持久化 composable
 *
 * 用法：
 *   const { widthOf } = useColumnWidths('invoice-list');
 *   <el-table @column-resize="onResize" ...>
 *     <el-table-column column-key="invoiceNo" :width="widthOf('invoiceNo', 160)" ... />
 *   </el-table>
 *
 * 必须给每个想持久化的列设 column-key（element-plus 列属性）。
 */
export function useColumnWidths(tableId: string) {
  const all = loadAll();
  if (!all[tableId]) all[tableId] = {};
  const slot = all[tableId];

  function widthOf(columnKey: string, fallback?: number): number | undefined {
    return slot[columnKey] ?? fallback;
  }

  /**
   * flex 列用：只传 :min-width、不传 :width。
   *
   * 背景：element-plus `table-layout.mjs:98-127` 只有在存在「没绑 width 的列」
   * （flexColumns.length > 0）时才会把表格撑到容器宽度：
   *   bodyWidth = max(bodyMinWidth, bodyWidth)
   * 若所有列都绑了 width，则走 else 分支 bodyWidth = 列宽之和，右侧会留白。
   *
   * 用户拖拽后的值在这里仍然生效，但语义是「最小宽度」而非「固定宽度」——
   * 既记住了用户偏好，又不会把列锁死、把表格重新压回留白状态。
   */
  function minOf(columnKey: string, fallback: number): number {
    return slot[columnKey] ?? fallback;
  }

  /** element-plus 列宽变化事件回调 */
  function onResize(newWidth: number, _oldWidth: number, column: { columnKey?: string; property?: string; type?: string }) {
    const key = column.columnKey || column.property;
    if (!key) return;
    if (column.type === 'selection' || column.type === 'index' || column.type === 'expand') return;
    slot[key] = newWidth;
    saveAll(all);
  }

  return { widthOf, minOf, onResize };
}

export function clearColumnWidths(tableId?: string) {
  if (!tableId) { localStorage.removeItem(STORAGE_KEY); return; }
  const all = loadAll();
  delete all[tableId];
  saveAll(all);
}