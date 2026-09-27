<template>
  <div class="block-data-table">
    <div class="block-data-table-title">{{ props.title }}</div>
    <div class="block-data-table-empty">
      <span class="muted">— {{ t('design.dataTableHint', { id: props.queryId || '?' }) }} —</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n';

const props = defineProps<{
  title?: string;
  queryId?: number;
  pageSize?: number;
  columns?: string | unknown[];
}>();

const { t } = useI18n();
</script>

<style scoped>
.block-data-table {
  display: flex; flex-direction: column; gap: 8px; height: 100%;
}
.block-data-table-title { font-size: 13px; color: var(--color-text-secondary); font-weight: 500; }
.block-data-table-empty {
  flex: 1; display: flex; align-items: center; justify-content: center;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-xs);
  background: var(--color-border-soft);
  font-size: 12px;
  min-height: 120px;
}
</style>