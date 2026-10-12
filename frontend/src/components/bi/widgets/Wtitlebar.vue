<script setup lang="ts">
/**
 * 大屏标题条（不取数）。主文案复用 widget.style.title —— 与其它组件共用
 * 「基础 → 标题」那个输入框，不另开一个副文案字段，否则用户改标题要去两个地方。
 * 副标题走 extra.subText。
 *
 * 三种变体：accent 左竖条 / solid 居中带副标题 / gradient 中间渐隐横条。
 */
import { computed } from 'vue';
import type { BiWidget } from '@/api/bi';

const props = defineProps<{ widget: BiWidget }>();

type Variant = 'accent' | 'solid' | 'gradient';

const variant = computed<Variant>(() => {
  const v = props.widget.extra?.titleStyle;
  return v === 'solid' || v === 'gradient' ? v : 'accent';
});

const align = computed(() => (props.widget.extra?.align === 'center' ? 'center' : 'left'));
const subText = computed(() => props.widget.extra?.subText || '');
const color = computed(() => props.widget.extra?.color || 'var(--bi-text)');
const rootStyle = computed(() => ({ '--tc': color.value }));
</script>

<template>
  <div class="w-titlebar" :class="[variant, `al-${align}`]" :style="rootStyle">
    <template v-if="variant === 'gradient'">
      <span class="rule"></span>
      <div class="stack">
        <div class="title">{{ widget.style.title }}</div>
        <div v-if="subText" class="sub">{{ subText }}</div>
      </div>
      <span class="rule"></span>
    </template>

    <template v-else-if="variant === 'solid'">
      <div class="stack">
        <div class="title">{{ widget.style.title }}</div>
        <div v-if="subText" class="sub">{{ subText }}</div>
      </div>
    </template>

    <template v-else>
      <span class="bar"></span>
      <div class="stack">
        <div class="title">{{ widget.style.title }}</div>
        <div v-if="subText" class="sub">{{ subText }}</div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.w-titlebar {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 100%;
  padding: 0 6px;
  min-height: 0;
}

/* accent：左侧一根主色竖条 */
.bar {
  flex: 0 0 4px;
  width: 4px;
  align-self: stretch;
  margin: 4px 0;
  background: var(--bi-accent);
  border-radius: 2px;
}

/* 中间两条线中间夹文字。渐隐靠 mask，纯 CSS 不引入图片 */
.rule {
  flex: 1 1 0;
  height: 1px;
  background: linear-gradient(to right, transparent, var(--bi-border-strong, var(--bi-accent)));
}
.rule:last-child {
  background: linear-gradient(to left, transparent, var(--bi-border-strong, var(--bi-accent)));
}

.stack {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}
.al-center .stack { justify-content: center; }

.title {
  font-size: 18px;
  font-weight: 600;
  color: var(--tc);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sub {
  font-size: 12px;
  color: var(--bi-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>