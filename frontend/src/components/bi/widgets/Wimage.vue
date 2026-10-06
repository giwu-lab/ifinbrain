<script setup lang="ts">
/** 图片：src 支持 http(s) 与项目内可访问的相对路径 */
import { computed } from 'vue';
import type { BiWidget } from '@/api/bi';

const props = defineProps<{ widget: BiWidget }>();

const src = computed(() => String(props.widget.extra?.src ?? ''));
const alt = computed(() => String(props.widget.extra?.alt ?? ''));
const fitClass = computed(() => `fit-${props.widget.extra?.fit || 'cover'}`);
</script>

<template>
  <div class="w-image">
    <img v-if="src" :src="src" :alt="alt" :class="fitClass" />
    <div v-else class="placeholder">{{ $t('bi.props.setImageUrl') }}</div>
  </div>
</template>

<style scoped>
.w-image {
  width: 100%;
  height: 100%;
  overflow: hidden;
}
.w-image img {
  width: 100%;
  height: 100%;
  display: block;
}
.fit-cover { object-fit: cover; }
.fit-contain { object-fit: contain; }
.fit-fill { object-fit: fill; }
.fit-none { object-fit: none; }
.placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--bi-text-muted);
  font-size: 13px;
  border: 1px dashed var(--bi-border);
  border-radius: 4px;
}
</style>