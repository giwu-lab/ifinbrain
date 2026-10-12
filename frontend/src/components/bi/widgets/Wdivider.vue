<script setup lang="ts">
/**
 * 分割线（不取数）。三种线型 + 一个「带标题」形态。
 * 原来只有一条实线（写在 BiWidgetBody 里一行 ::before），这里提成组件并扩出变体。
 */
import { computed } from 'vue';
import type { BiWidget } from '@/api/bi';

const props = defineProps<{ widget: BiWidget }>();

type LineStyle = 'solid' | 'dashed' | 'dotted';

const lineStyle = computed<LineStyle>(() => {
  const v = props.widget.extra?.lineStyle;
  return v === 'dashed' || v === 'dotted' ? v : 'solid';
});

const withTitle = computed(() => props.widget.extra?.withTitle === true);
const title = computed(() => props.widget.extra?.title || '');
const color = computed(() => props.widget.extra?.color || 'var(--bi-accent)');
const thickness = computed(() => Number(props.widget.extra?.thickness) || 1);
</script>

<template>
  <div class="w-divider" :class="{ 'with-title': withTitle }">
    <template v-if="withTitle">
      <span class="rule" :style="{ '--lc': color, '--lw': thickness + 'px' }"></span>
      <span v-if="title" class="label">{{ title }}</span>
      <span class="rule" :style="{ '--lc': color, '--lw': thickness + 'px' }"></span>
    </template>
    <span
      v-else
      class="rule full"
      :class="lineStyle"
      :style="{ '--lc': color, '--lw': thickness + 'px' }"
    ></span>
  </div>
</template>

<style scoped>
.w-divider {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 100%;
  padding: 0 2px;
}
.w-divider:not(.with-title) { display: block; }

.rule {
  flex: 1 1 0;
  height: var(--lw, 1px);
  background: var(--lc, var(--bi-accent));
  opacity: 0.6;
  min-width: 0;
}
.rule.full { display: block; width: 100%; }
.rule.dashed {
  background: none;
  border-top: var(--lw, 1px) dashed var(--lc, var(--bi-accent));
  height: 0;
}
.rule.dotted {
  background: none;
  border-top: var(--lw, 1px) dotted var(--lc, var(--bi-accent));
  height: 0;
}

.label {
  flex: 0 0 auto;
  font-size: 12px;
  color: var(--bi-text-secondary);
  white-space: nowrap;
}
</style>