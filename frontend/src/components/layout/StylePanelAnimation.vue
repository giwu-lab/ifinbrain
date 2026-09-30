<template>
  <section class="sp-section">
    <header class="sp-section-header">
      <span class="sp-section-title">{{ t('stylePanel.animation') }}</span>
    </header>

    <div class="sp-row">
      <span class="sp-label">{{ t('stylePanel.enableAnimation') }}</span>
      <el-switch :model-value="enableAnimation" @update:model-value="(v: any) => emit('update:enableAnimation', v as boolean)" />
    </div>

    <div v-if="enableAnimation" class="sp-anim-grid">
      <button
        v-for="opt in options"
        :key="opt.value"
        type="button"
        class="sp-anim"
        :class="{ active: animationType === opt.value }"
        :title="opt.label"
        @click="$emit('update:animationType', opt.value)"
      >
        <div class="sp-anim-thumb" :data-anim="opt.value">
          <div class="sp-anim-card" />
          <div class="sp-anim-card sp-anim-card-2" />
        </div>
        <span class="sp-anim-label">{{ opt.label }}</span>
      </button>
    </div>

    <div class="sp-row">
      <span class="sp-label">{{ t('stylePanel.progressBar') }}</span>
      <el-switch :model-value="enableProgress" @update:model-value="(v: any) => emit('update:enableProgress', v as boolean)" />
    </div>

    <div class="sp-row">
      <span class="sp-label">{{ t('stylePanel.enableLoading') }}</span>
      <el-switch :model-value="enableLoading" @update:model-value="(v: any) => emit('update:enableLoading', v as boolean)" />
    </div>
  </section>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n';
import type { AnimationType } from '@/stores/theme';

defineProps<{
  enableAnimation: boolean;
  animationType: AnimationType;
  enableProgress: boolean;
  enableLoading: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:enableAnimation', v: boolean): void;
  (e: 'update:animationType', v: AnimationType): void;
  (e: 'update:enableProgress', v: boolean): void;
  (e: 'update:enableLoading', v: boolean): void;
}>();

const { t } = useI18n();

const options: { value: AnimationType; label: string }[] = [
  { value: 'fade',       label: t('stylePanel.animFade') },
  { value: 'slide',      label: t('stylePanel.animSlide') },
  { value: 'zoom',       label: t('stylePanel.animZoom') },
  { value: 'fade-slide', label: t('stylePanel.animFadeSlide') },
  { value: 'flip',       label: t('stylePanel.animFlip') },
  { value: 'scale',      label: t('stylePanel.animScale') },
];
</script>

<style scoped>
.sp-section { padding: 16px 0; border-bottom: 1px solid var(--color-border-soft); }
.sp-section-header { display: flex; justify-content: center; margin-bottom: 14px; }
.sp-section-title { font-size: 14px; font-weight: 600; color: var(--color-text); }

.sp-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
}
.sp-label { font-size: 13px; color: var(--color-text-secondary); }

.sp-anim-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin: 10px 0 14px;
}
.sp-anim {
  background: var(--color-bg-elev);
  border: 1px solid var(--color-border);
  border-radius: 6px;
  padding: 8px 6px 4px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  transition: all 0.15s ease;
}
.sp-anim:hover { border-color: var(--color-primary); }
.sp-anim.active { border-color: var(--color-primary); box-shadow: 0 0 0 2px var(--color-primary-soft); }
.sp-anim-label { font-size: 11px; color: var(--color-text-secondary); }

.sp-anim-thumb {
  width: 100%;
  height: 32px;
  background: var(--color-bg);
  border-radius: 4px;
  position: relative;
  overflow: hidden;
}
.sp-anim-card {
  position: absolute;
  inset: 4px 30% 4px 4px;
  background: var(--color-primary);
  border-radius: 3px;
  opacity: 0.85;
}
.sp-anim-card-2 {
  inset: 4px 4px 4px 30%;
  background: var(--color-primary-soft);
  border: 1px dashed var(--color-primary);
  opacity: 0.7;
}

/* thumb 上做个 hover 微动画预览：选中的高亮，其余静态 */
.sp-anim.active .sp-anim-card { animation: sp-anim-show 1.6s ease-in-out infinite; }
@keyframes sp-anim-show {
  0%   { transform: translateX(-100%); opacity: 0; }
  40%  { transform: translateX(0);     opacity: 1; }
  100% { transform: translateX(0);     opacity: 1; }
}
</style>