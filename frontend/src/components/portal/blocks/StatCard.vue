<template>
  <div class="block-stat-card" :class="`color-${props.color || 'primary'}`">
    <div class="block-stat-label">{{ props.title }}</div>
    <div class="block-stat-value-row">
      <span class="block-stat-value">{{ formatValue() }}</span>
      <span v-if="props.suffix" class="block-stat-suffix">{{ props.suffix }}</span>
      <span v-if="props.trend" class="block-stat-trend" :class="trendClass()">{{ props.trend }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
  title?: string;
  value?: number | string;
  suffix?: string;
  trend?: string;
  color?: 'primary' | 'success' | 'warning' | 'danger' | 'info';
}>();

function formatValue() {
  if (typeof props.value === 'number') {
    return props.value.toLocaleString();
  }
  return props.value ?? '';
}

function trendClass() {
  const t = (props.trend || '').trim();
  if (t.startsWith('+')) return 'is-up';
  if (t.startsWith('-')) return 'is-down';
  return '';
}
</script>

<style scoped>
.block-stat-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 4px 0;
}
.block-stat-label {
  font-size: 13px;
  color: var(--color-text-secondary);
}
.block-stat-value-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.block-stat-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--color-text);
  font-family: ui-monospace, 'JetBrains Mono', monospace;
  letter-spacing: -0.01em;
}
.block-stat-suffix {
  font-size: 13px;
  color: var(--color-text-secondary);
}
.block-stat-trend {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 10px;
  background: var(--color-border-soft);
  color: var(--color-text-secondary);
}
.block-stat-trend.is-up { background: #e6f4ea; color: #0e8f5c; }
.block-stat-trend.is-down { background: #fce8e6; color: #c8392f; }
.color-primary .block-stat-value  { color: var(--color-primary); }
.color-success .block-stat-value  { color: #0e8f5c; }
.color-warning .block-stat-value  { color: #c08016; }
.color-danger  .block-stat-value  { color: #c8392f; }
.color-info    .block-stat-value  { color: #4f7cc9; }
</style>