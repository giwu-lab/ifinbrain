<template>
  <section class="sp-section">
    <header class="sp-section-header">
      <span class="sp-section-title">{{ title }}</span>
    </header>

    <div class="sp-color-row">
      <el-color-picker
        :model-value="color"
        size="small"
        show-alpha
        @active-change="onPicker"
      />
      <span class="sp-hex">{{ color.toUpperCase() }}</span>
      <span class="sp-opacity">{{ t('stylePanel.colorOpacity') }}</span>
      <el-slider
        :model-value="opacity"
        :min="0"
        :max="100"
        :step="5"
        class="sp-slider"
        @input="onSlider"
      />
      <span class="sp-opacity-value">A {{ opacity }} %</span>
    </div>

    <div class="sp-swatches">
      <button
        v-for="c in presets"
        :key="c"
        type="button"
        class="sp-swatch"
        :class="{ active: c.toLowerCase() === color.toLowerCase() }"
        :style="{ background: c }"
        :title="c"
        @click="onPick(c)"
      />
    </div>
  </section>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n';

const props = defineProps<{
  title: string;
  color: string;
  opacity: number;
  presets: string[];
}>();

const emit = defineEmits<{
  (e: 'update:color', v: string): void;
  (e: 'update:opacity', v: number): void;
}>();

const { t } = useI18n();

function onPicker(v: string | null) {
  if (!v) return;
  // el-color-picker 输出 '#rrggbb' 或 'rgba(...)' —— 统一归一为 '#rrggbb'
  const norm = v.startsWith('#') ? v : rgbaToHex(v);
  emit('update:color', norm);
}
function onPick(c: string) { emit('update:color', c); }
function onSlider(v: number | number[]) {
  emit('update:opacity', Array.isArray(v) ? v[0] : v);
}

function rgbaToHex(v: string): string {
  const m = v.match(/rgba?\(([^)]+)\)/i);
  if (!m) return v;
  const [r, g, b] = m[1].split(',').map((x) => parseInt(x.trim(), 10));
  return '#' + [r, g, b].map((x) => x.toString(16).padStart(2, '0')).join('');
}
</script>

<style scoped>
.sp-section {
  padding: 16px 0;
  border-bottom: 1px solid var(--color-border-soft);
}
.sp-section-header {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 14px;
}
.sp-section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text);
}

.sp-color-row {
  display: grid;
  grid-template-columns: auto 1fr auto;
  grid-template-rows: auto auto;
  align-items: center;
  gap: 8px 10px;
  margin-bottom: 14px;
}
.sp-color-row :deep(.el-color-picker) { width: 32px; }
.sp-color-row :deep(.el-color-picker__trigger) { width: 32px; height: 28px; padding: 2px; }
.sp-hex {
  font-family: 'SF Mono', Menlo, monospace;
  font-size: 12px;
  color: var(--color-text-secondary);
  letter-spacing: 0;
}
.sp-opacity {
  grid-column: 1 / -1;
  font-size: 12px;
  color: var(--color-text-muted);
  margin-top: 4px;
}
.sp-slider {
  grid-column: 1 / 3;
  --el-slider-runway-bg-color: var(--color-border);
}
.sp-opacity-value {
  grid-column: 3;
  font-size: 11px;
  color: var(--color-text-muted);
  font-family: 'SF Mono', Menlo, monospace;
}

.sp-swatches {
  display: grid;
  grid-template-columns: repeat(12, 1fr);
  gap: 6px;
}
.sp-swatch {
  aspect-ratio: 1 / 1;
  border: 1px solid var(--color-border-soft);
  border-radius: 4px;
  cursor: pointer;
  padding: 0;
  outline: none;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}
.sp-swatch:hover { transform: scale(1.08); }
.sp-swatch.active {
  box-shadow: 0 0 0 2px var(--color-primary);
  transform: scale(1.08);
}
</style>