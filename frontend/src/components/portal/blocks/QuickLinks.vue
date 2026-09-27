<template>
  <div class="block-quick-links">
    <div class="block-quick-links-title">{{ props.title }}</div>
    <div class="block-quick-links-list">
      <a v-for="(link, i) in parsed" :key="i" class="block-quick-link" :href="link.to" @click.prevent="onClick(link.to)">
        <el-icon v-if="link.icon"><component :is="resolveIcon(link.icon)" /></el-icon>
        <span>{{ link.label }}</span>
      </a>
      <span v-if="parsed.length === 0" class="muted small">— {{ t('design.linksEmpty') }} —</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import * as ElIcons from '@element-plus/icons-vue';
import { useI18n } from 'vue-i18n';

const props = defineProps<{
  title?: string;
  links?: string | Array<{ label: string; to: string; icon?: string }>;
}>();

const { t } = useI18n();
const router = useRouter();

const parsed = computed(() => {
  if (Array.isArray(props.links)) return props.links;
  if (typeof props.links === 'string') {
    try { return JSON.parse(props.links); } catch { return []; }
  }
  return [];
});

function resolveIcon(name?: string) {
  if (!name) return ElIcons.Link;
  return (ElIcons as Record<string, unknown>)[name] as any || ElIcons.Link;
}

function onClick(to: string) {
  if (!to) return;
  if (to.startsWith('http')) {
    window.open(to, '_blank');
  } else {
    router.push(to);
  }
}
</script>

<style scoped>
.block-quick-links {
  display: flex; flex-direction: column; gap: 10px;
}
.block-quick-links-title { font-size: 13px; color: var(--color-text-secondary); font-weight: 500; }
.block-quick-links-list {
  display: flex; flex-wrap: wrap; gap: 8px;
}
.block-quick-link {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 6px 12px;
  border: 1px solid var(--color-border);
  border-radius: 16px;
  font-size: 13px;
  color: var(--color-text);
  background: var(--color-bg-elev);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.block-quick-link:hover {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
  color: var(--color-primary);
}
.block-quick-link .el-icon { font-size: 14px; }
</style>