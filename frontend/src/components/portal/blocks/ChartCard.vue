<template>
  <div class="block-chart-card">
    <div class="block-chart-title">{{ props.title }}</div>
    <div class="block-chart-body">
      <div v-if="parsed.data.length === 0" class="block-chart-empty">
        — {{ t('design.chartNoData') }} —
      </div>
      <template v-else>
        <div v-if="props.kind === 'bar'" class="block-chart-bars">
          <div v-for="(d, i) in parsed.data" :key="i" class="block-chart-bar">
            <div class="block-chart-bar-label">{{ d[parsed.xField] }}</div>
            <div class="block-chart-bar-track">
              <div class="block-chart-bar-fill" :style="{ width: barWidth(d[parsed.yField]) + '%' }" />
            </div>
            <div class="block-chart-bar-value">{{ d[parsed.yField] }}</div>
          </div>
        </div>
        <div v-else-if="props.kind === 'line'" class="block-chart-line">
          <svg viewBox="0 0 200 80" preserveAspectRatio="none">
            <polyline :points="linePoints" fill="none" stroke="var(--color-primary)" stroke-width="1.5" />
          </svg>
          <div class="block-chart-line-axis">
            <span v-for="(d, i) in parsed.data" :key="i">{{ d[parsed.xField] }}</span>
          </div>
        </div>
        <div v-else class="block-chart-empty">chart kind: {{ props.kind }}（预览模式）</div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';

const props = defineProps<{
  title?: string;
  kind?: 'bar' | 'line' | 'pie';
  data?: string | unknown[];
  xField?: string;
  yField?: string;
}>();

const { t } = useI18n();

const parsed = computed(() => {
  let arr: any[] = [];
  if (typeof props.data === 'string') {
    try { arr = JSON.parse(props.data); } catch { arr = []; }
  } else if (Array.isArray(props.data)) {
    arr = props.data;
  }
  const xField = props.xField || 'name';
  const yField = props.yField || 'value';
  return { data: arr, xField, yField };
});

function barWidth(v: unknown) {
  const num = Number(v);
  if (!isFinite(num) || parsed.value.data.length === 0) return 0;
  const max = Math.max(...parsed.value.data.map(d => Number(d[parsed.value.yField]) || 0), 1);
  return Math.max(0, Math.min(100, (num / max) * 100));
}

const linePoints = computed(() => {
  const arr = parsed.value.data;
  if (arr.length === 0) return '';
  const yField = parsed.value.yField;
  const max = Math.max(...arr.map(d => Number(d[yField]) || 0), 1);
  const stepX = 200 / Math.max(arr.length - 1, 1);
  return arr.map((d, i) => {
    const v = Number(d[yField]) || 0;
    const y = 80 - (v / max) * 70 - 5;
    return `${i * stepX},${y}`;
  }).join(' ');
});
</script>

<style scoped>
.block-chart-card {
  display: flex; flex-direction: column; gap: 10px;
  height: 100%;
}
.block-chart-title {
  font-size: 13px;
  color: var(--color-text-secondary);
  font-weight: 500;
}
.block-chart-body {
  flex: 1;
  min-height: 120px;
}
.block-chart-empty {
  height: 100%;
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; color: var(--color-text-muted);
  background: var(--color-border-soft);
  border-radius: var(--radius-xs);
}
.block-chart-bars { display: flex; flex-direction: column; gap: 8px; }
.block-chart-bar { display: grid; grid-template-columns: 80px 1fr 50px; gap: 8px; align-items: center; font-size: 12px; }
.block-chart-bar-label { color: var(--color-text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.block-chart-bar-track { background: var(--color-border-soft); height: 8px; border-radius: 4px; overflow: hidden; }
.block-chart-bar-fill { background: var(--color-primary); height: 100%; transition: width 0.3s; }
.block-chart-bar-value { font-family: ui-monospace, monospace; text-align: right; color: var(--color-text); }
.block-chart-line svg { width: 100%; height: 80px; display: block; }
.block-chart-line-axis { display: flex; justify-content: space-between; font-size: 11px; color: var(--color-text-muted); padding-top: 4px; }
</style>