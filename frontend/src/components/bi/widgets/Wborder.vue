<script setup lang="ts">
/**
 * 装饰边框（不取数）。三种变体：
 *   corner — 四角角标，DataV 大屏最常见的框
 *   full   — 整圈 1px 描边
 *   line   — 上下两条横线
 *
 * 颜色默认跟主题主色走 var(--bi-accent)，所以大屏切主题时边框自动跟着变；
 * 手工填了 color 才覆盖。
 */
import { computed } from 'vue';
import type { BiWidget } from '@/api/bi';

const props = defineProps<{ widget: BiWidget }>();

type Variant = 'corner' | 'full' | 'line';

const CORNERS = ['tl', 'tr', 'bl', 'br'] as const;

const variant = computed<Variant>(() => {
  const v = props.widget.extra?.borderStyle;
  return v === 'full' || v === 'line' ? v : 'corner';
});

const color = computed(() => props.widget.extra?.color || 'var(--bi-accent)');
const thickness = computed(() => Number(props.widget.extra?.thickness) || 2);
const cornerLen = computed(() => Number(props.widget.extra?.cornerLen) || 28);

const rootStyle = computed(() => ({
  '--bc': color.value,
  '--bw': `${thickness.value}px`,
  '--bl': `${cornerLen.value}px`,
}));
</script>

<template>
  <div class="w-border" :style="rootStyle">
    <template v-if="variant === 'corner'">
      <span v-for="c in CORNERS" :key="c" class="corner" :class="c"></span>
    </template>
    <span v-else-if="variant === 'full'" class="full"></span>
    <template v-else>
      <span class="hline top"></span>
      <span class="hline bottom"></span>
    </template>
  </div>
</template>

<style scoped>
.w-border {
  position: relative;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

/* 每个角是两根线的组合：横的一段 + 竖的一段 */
.corner {
  position: absolute;
  width: var(--bl);
  height: var(--bl);
}
.tl { top: 0; left: 0; border-top: var(--bw) solid var(--bc); border-left: var(--bw) solid var(--bc); }
.tr { top: 0; right: 0; border-top: var(--bw) solid var(--bc); border-right: var(--bw) solid var(--bc); }
.bl { bottom: 0; left: 0; border-bottom: var(--bw) solid var(--bc); border-left: var(--bw) solid var(--bc); }
.br { bottom: 0; right: 0; border-bottom: var(--bw) solid var(--bc); border-right: var(--bw) solid var(--bc); }

.full {
  position: absolute;
  inset: 0;
  border: var(--bw) solid var(--bc);
  border-radius: var(--bi-radius, 0);
}

.hline {
  position: absolute;
  left: 0;
  right: 0;
  height: var(--bw);
  background: var(--bc);
}
.hline.top { top: 0; }
.hline.bottom { bottom: 0; }
</style>