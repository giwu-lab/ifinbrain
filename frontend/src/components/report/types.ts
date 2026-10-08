import type { ColumnCfg } from '@/api/report';

/** 虚拟表格用的列定义（渲染层形态；配置层是 ColumnCfg） */
export interface TableV2Column {
  key: string;
  label: string;
  width: number;
  align: 'left' | 'center' | 'right';
  fixed: boolean;
}

/** ColumnCfg[] → TableV2Column[]；header 已由 utils/report/header.ts 的 mergeColumns 兜过 */
export function toTableColumns(cols: ColumnCfg[]): TableV2Column[] {
  return cols.map((c) => ({
    key: c.key,
    label: c.label || c.key,
    width: c.width ?? 140,
    align: (c.align ?? 'left') as 'left' | 'center' | 'right',
    fixed: c.fixed ?? false,
  }));
}
