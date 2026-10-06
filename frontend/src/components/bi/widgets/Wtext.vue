<script setup lang="ts">
/** 文本 / 标题。内容走 v-text（textContent），不 v-html —— 避免 XSS 面。 */
import { computed } from 'vue';
import type { CSSProperties } from 'vue';
import type { BiWidget } from '@/api/bi';

const props = defineProps<{ widget: BiWidget }>();

const content = computed(() => String(props.widget.extra?.content ?? ''));
const align = computed(() => (props.widget.extra?.align as string) || 'center');
const fontSize = computed(() => Number(props.widget.extra?.fontSize ?? 24));
const color = computed(() => String(props.widget.extra?.color ?? 'var(--bi-text)'));
const bold = computed(() => props.widget.extra?.bold !== false);
const letterSpacing = computed(() => Number(props.widget.extra?.letterSpacing ?? 0));

const textStyle = computed<CSSProperties>(() => ({
  textAlign: align.value as CSSProperties['textAlign'],
  fontSize: `${fontSize.value}px`,
  color: color.value,
  fontWeight: bold.value ? 700 : 400,
  letterSpacing: `${letterSpacing.value}px`,
}));
</script>

<template>
  <div class="w-text" :style="textStyle">{{ content }}</div>
</template>

<style scoped>
.w-text {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  overflow: hidden;
  line-height: 1.35;
  word-break: break-word;
}
</style>