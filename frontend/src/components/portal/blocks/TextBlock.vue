<template>
  <div class="block-text" :class="`variant-${props.variant || 'plain'}`">
    <div v-if="props.title" class="block-text-title">{{ props.title }}</div>
    <div class="block-text-body">{{ props.content }}</div>
  </div>
</template>

<script setup lang="ts">
const props = defineProps<{
  title?: string;
  content?: string;
  variant?: 'plain' | 'callout' | 'code';
}>();
</script>

<style scoped>
.block-text {
  display: flex; flex-direction: column; gap: 6px;
}
.block-text-title { font-size: 13px; font-weight: 500; color: var(--color-text); }
.block-text-body  { font-size: 13px; color: var(--color-text-secondary); line-height: 1.6; white-space: pre-wrap; }
.variant-callout .block-text-body {
  background: var(--color-primary-soft);
  border-left: 3px solid var(--color-primary);
  padding: 10px 14px;
  border-radius: var(--radius-xs);
  color: var(--color-text);
}
.variant-code .block-text-body {
  background: #1f2937;
  color: #e5e7eb;
  font-family: ui-monospace, 'JetBrains Mono', monospace;
  padding: 10px 14px;
  border-radius: var(--radius-xs);
}
</style>