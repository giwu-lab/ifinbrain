<script setup lang="ts">
/** 指标卡：主值 + 单位 + 副标签。刻意不走 ECharts（DOM 更快、无实例开销）。 */
import { computed } from 'vue';
import type { BiDataResp, BiWidget } from '@/api/bi';
import { colIndex } from '@/utils/bi/layout';
import { formatNumber } from '@/utils/bi/chartOption';

const props = defineProps<{
  widget: BiWidget;
  data: BiDataResp | null;
}>();

const unit = computed(() => String(props.widget.extra?.unit ?? ''));
const prefix = computed(() => String(props.widget.extra?.prefix ?? ''));
const decimals = computed(() => Number(props.widget.extra?.decimals ?? 2));
const agg = computed(() => props.widget.data?.agg || 'none');

/**
 * KPI 取值必须尊重数据绑定里的聚合方式 —— 绑定面板让用户选了「求和」，
 * 这里却只取第一行的话，用户配了 agg 却没有任何效果（且会静默给出错误数字）。
 *   none  → 第一行值（明细型指标，如「本期值」）
 *   count → 行数
 *   其余  → 对该指标列做 sum / avg / max / min
 */
const raw = computed<any>(() => {
  const b = props.widget.data;
  const rows = props.data?.rows;
  if (!b || !rows?.length) return null;

  const cols = props.data!.columns || [];
  const key = b.mapping.y?.[0] ?? b.mapping.value;
  const idx = key ? colIndex(cols, key) : 0;

  if (agg.value === 'count') return rows.length;
  if (agg.value === 'none') return rows[0]?.[idx];

  const nums = rows
    .map((r) => Number(Array.isArray(r) ? r[idx] : NaN))
    .filter((n) => Number.isFinite(n));
  if (!nums.length) return 0;
  const sum = nums.reduce((a, c) => a + c, 0);
  switch (agg.value) {
    case 'sum': return sum;
    case 'avg': return sum / nums.length;
    case 'max': return Math.max(...nums);
    case 'min': return Math.min(...nums);
    default: return rows[0]?.[idx];
  }
});

const shown = computed(() => {
  if (raw.value === null || raw.value === undefined || raw.value === '') return '-';
  return formatNumber(raw.value, decimals.value);
});

const sub = computed(() => {
  const b = props.widget.data;
  if (!b || !props.data?.rows?.length) return '';
  const cols = props.data.columns || [];
  const idx = colIndex(cols, b.mapping.x);
  const first = props.data.rows[0];
  if (idx < 0 || !Array.isArray(first)) return '';
  return String(first[idx] ?? '');
});
</script>

<template>
  <div class="kpi">
    <div class="kpi-value">
      <span v-if="prefix" class="kpi-affix">{{ prefix }}</span>
      <span class="kpi-num">{{ shown }}</span>
      <span v-if="unit" class="kpi-unit">{{ unit }}</span>
    </div>
    <div v-if="sub" class="kpi-sub">{{ sub }}</div>
  </div>
</template>

<style scoped>
.kpi {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 6px;
  width: 100%;
  height: 100%;
  min-height: 0;
}
.kpi-value {
  display: flex;
  align-items: baseline;
  gap: 4px;
}
.kpi-num {
  font-size: 38px;
  font-weight: 700;
  line-height: 1.1;
  color: var(--bi-accent);
  font-variant-numeric: tabular-nums;
}
.kpi-affix {
  font-size: 15px;
  color: var(--bi-text-secondary);
}
.kpi-unit {
  font-size: 14px;
  color: var(--bi-text-secondary);
}
.kpi-sub {
  font-size: 13px;
  color: var(--bi-text-muted);
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}
</style>