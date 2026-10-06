<script setup lang="ts">
/** 明细表：直接吃查询结果，不做聚合（表格要看原始行） */
import { computed } from 'vue';
import type { BiDataResp, BiWidget } from '@/api/bi';
import { applyTransform, colIndex } from '@/utils/bi/layout';

const props = defineProps<{
  widget: BiWidget;
  data: BiDataResp | null;
}>();

const maxRows = computed(() => Number(props.widget.extra?.maxRows ?? 20));
const showIndex = computed(() => props.widget.extra?.showIndex !== false);

const table = computed(() => {
  if (!props.data?.rows?.length) return null;
  const bind = props.widget.data;
  let rows = props.data.rows;
  const dir = (props.widget.extra?.sortDir as 'asc' | 'desc') || 'desc';
  const field = props.widget.extra?.sortField;
  if (field) {
    const idx = colIndex(props.data.columns, field);
    rows = applyTransform(rows, idx, dir);
  }
  rows = rows.slice(0, maxRows.value);
  return {
    columns: props.data.columns.map((c) => ({ key: c.key, label: c.label || c.key })),
    rows,
  };
});

/** 优先用表格定义里的列顺序；没定义就全量展示 */
const visibleColumns = computed(() => {
  const defined = props.widget.extra?.columns as string[] | undefined;
  if (!table.value) return [];
  if (!defined?.length) return table.value.columns;
  return defined
    .map((k) => table.value!.columns.find((c) => c.key === k))
    .filter(Boolean) as { key: string; label: string }[];
});

function rowCells(row: any[]) {
  return visibleColumns.value.map((c) => {
    const i = table.value!.columns.findIndex((x) => x.key === c.key);
    return row[i];
  });
}
</script>

<template>
  <div class="bi-table">
    <div v-if="!table" class="bi-table-empty">{{ $t('bi.view.noData') }}</div>
    <table v-else class="tbl">
      <thead>
        <tr>
          <th v-if="showIndex" class="idx">#</th>
          <th v-for="c in visibleColumns" :key="c.key">{{ c.label }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(r, i) in table.rows" :key="i">
          <td v-if="showIndex" class="idx tabular">{{ i + 1 }}</td>
          <td v-for="(v, j) in rowCells(r)" :key="j" :title="String(v ?? '')">{{ v ?? '-' }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.bi-table {
  width: 100%;
  height: 100%;
  overflow: hidden;
  min-height: 0;
}
.bi-table-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--bi-text-muted);
  font-size: 13px;
}
.tbl {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.tbl th,
.tbl td {
  padding: 6px 8px;
  text-align: left;
  border-bottom: 1px solid var(--bi-border);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 0;
}
.tbl th {
  color: var(--bi-text-secondary);
  font-weight: 600;
  background: var(--bi-panel-2);
  position: sticky;
  top: 0;
}
.tbl td {
  color: var(--bi-text);
}
.tbl tbody tr:hover td {
  background: var(--bi-accent-soft);
}
.idx {
  width: 44px;
  color: var(--bi-text-muted);
}
</style>