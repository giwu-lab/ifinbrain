<template>
  <section class="sp-section">
    <header class="sp-section-header">
      <span class="sp-section-title">{{ title }}</span>
    </header>

    <div class="sp-layout-grid">
      <button
        v-for="opt in options"
        :key="opt.value"
        type="button"
        class="sp-layout"
        :class="{ active: modelValue === opt.value }"
        :title="opt.label"
        @click="$emit('update:modelValue', opt.value)"
      >
        <svg viewBox="0 0 56 36" class="sp-layout-svg">
          <rect x="0.5" y="0.5" width="55" height="35" rx="3" class="sp-frame" />
          <template v-if="opt.value === 'sidebar'">
            <rect x="3" y="3" width="14" height="30" rx="2" class="sp-side" />
            <rect x="19" y="3" width="34" height="6" rx="1.5" class="sp-head" />
            <rect x="19" y="11" width="34" height="22" rx="1.5" class="sp-body" />
          </template>
          <template v-else-if="opt.value === 'topnav'">
            <rect x="3" y="3" width="50" height="6" rx="1.5" class="sp-head" />
            <rect x="3" y="11" width="50" height="22" rx="1.5" class="sp-body" />
          </template>
          <template v-else-if="opt.value === 'header-sidebar'">
            <rect x="3" y="3" width="50" height="6" rx="1.5" class="sp-head" />
            <rect x="3" y="11" width="14" height="22" rx="1.5" class="sp-side" />
            <rect x="19" y="11" width="34" height="22" rx="1.5" class="sp-body" />
          </template>
          <template v-else-if="opt.value === 'floating-sidebar'">
            <rect x="3" y="3" width="14" height="30" rx="3" class="sp-side-floating" />
            <rect x="20" y="3" width="33" height="30" rx="3" class="sp-body-floating" />
          </template>
        </svg>
      </button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n';
import type { LayoutType } from '@/stores/theme';

defineProps<{ modelValue: LayoutType; title: string }>();
defineEmits<{ (e: 'update:modelValue', v: LayoutType): void }>();

const { t } = useI18n();

const options: { value: LayoutType; label: string }[] = [
  { value: 'sidebar',          label: t('stylePanel.layoutSidebar') },
  { value: 'topnav',           label: t('stylePanel.layoutTopnav') },
  { value: 'header-sidebar',   label: t('stylePanel.layoutHeaderSidebar') },
  { value: 'floating-sidebar', label: t('stylePanel.layoutFloatingSidebar') },
];
</script>

<style scoped>
.sp-section { padding: 16px 0; border-bottom: 1px solid var(--color-border-soft); }
.sp-section-header { display: flex; justify-content: center; margin-bottom: 14px; }
.sp-section-title { font-size: 14px; font-weight: 600; color: var(--color-text); }

.sp-layout-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
}
.sp-layout {
  padding: 6px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-bg-elev);
  cursor: pointer;
  transition: all 0.15s ease;
  display: flex;
  align-items: center;
  justify-content: center;
}
.sp-layout:hover { border-color: var(--color-primary); }
.sp-layout.active { border-color: var(--color-primary); box-shadow: 0 0 0 2px var(--color-primary-soft); }

.sp-layout-svg { width: 100%; height: auto; max-height: 50px; }
.sp-frame { fill: var(--color-bg); stroke: var(--color-border); }
.sp-head { fill: var(--color-primary-soft); stroke: var(--color-primary); stroke-width: 0.5; }
.sp-body { fill: var(--color-bg-elev); stroke: var(--color-border); stroke-width: 0.5; }
.sp-side { fill: var(--color-primary); }
.sp-side-floating { fill: var(--color-primary); opacity: 0.85; }
.sp-body-floating { fill: var(--color-bg-elev); stroke: var(--color-primary-soft); stroke-width: 0.8; }
</style>