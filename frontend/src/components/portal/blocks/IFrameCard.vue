<template>
  <div class="block-iframe">
    <div class="block-iframe-title">{{ props.title }}</div>
    <div class="block-iframe-frame" :style="{ height: (props.height || 420) + 'px' }">
      <iframe v-if="props.src" :src="props.src" frameborder="0" />
      <div v-else class="block-iframe-empty muted">— {{ t('design.iframeEmpty') }} —</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n';

const props = defineProps<{
  title?: string;
  src?: string;
  height?: number;
}>();

const { t } = useI18n();
</script>

<style scoped>
.block-iframe { display: flex; flex-direction: column; gap: 8px; }
.block-iframe-title { font-size: 13px; color: var(--color-text-secondary); font-weight: 500; }
.block-iframe-frame {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xs);
  overflow: hidden;
  background: var(--color-border-soft);
  position: relative;
}
.block-iframe-frame iframe { width: 100%; height: 100%; display: block; }
.block-iframe-empty {
  height: 100%; display: flex; align-items: center; justify-content: center;
  font-size: 12px;
}
</style>