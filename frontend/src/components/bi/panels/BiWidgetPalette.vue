<script setup lang="ts">
/** 左栏 Tab1：组件库。按 metric / chart / content 三类分组，点击即加入画布。 */
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import type { WidgetType } from '@/api/bi';
import { WIDGET_META, WIDGET_TYPES, type WidgetCategory } from '@/utils/bi/layout';

const emit = defineEmits<{ (e: 'add', type: WidgetType): void }>();

const { t } = useI18n();

const GROUPS: { key: WidgetCategory; labelKey: string }[] = [
  { key: 'metric', labelKey: 'bi.palette.metric' },
  { key: 'chart', labelKey: 'bi.palette.chart' },
  { key: 'content', labelKey: 'bi.palette.content' },
];

const groups = computed(() =>
  GROUPS.map((g) => ({
    ...g,
    items: WIDGET_TYPES.filter((ty) => WIDGET_META[ty].category === g.key),
  })),
);

function labelOf(ty: WidgetType) {
  return t(`bi.widget.${ty}`, WIDGET_META[ty].labelKey || ty);
}
</script>

<template>
  <div class="bi-palette">
    <div v-for="g in groups" :key="g.key" class="grp">
      <div class="grp-title">{{ t(g.labelKey) }}</div>
      <div class="grp-grid">
        <button
          v-for="ty in g.items"
          :key="ty"
          type="button"
          class="item"
          :title="labelOf(ty)"
          @click="emit('add', ty)"
        >
          <el-icon class="item-icon">
            <component :is="WIDGET_META[ty].icon" />
          </el-icon>
          <span class="item-label">{{ labelOf(ty) }}</span>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bi-palette {
  padding: 10px;
}
.grp + .grp {
  margin-top: 14px;
}
.grp-title {
  font-size: 12px;
  color: var(--color-text-muted);
  margin-bottom: 8px;
}
.grp-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}
.item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 10px 4px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-bg-elev);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.item:hover {
  border-color: var(--color-primary);
  background: var(--color-primary-softer, var(--color-primary-soft));
}
.item-icon {
  font-size: 18px;
  color: var(--color-primary);
}
.item-label {
  font-size: 12px;
  color: var(--color-text-secondary);
  text-align: center;
  line-height: 1.2;
}
</style>