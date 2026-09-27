<template>
  <div class="page-container fade-in-up df-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('design.flowTitle') }}</h1>
        <p class="page-desc muted">{{ t('design.flowDesc') }}</p>
      </div>
    </div>

    <div class="df-placeholder">
      <div class="df-placeholder-inner">
        <el-icon class="df-placeholder-icon"><Share /></el-icon>
        <h3 class="df-placeholder-title">{{ t('design.flowPlaceholderTitle') }}</h3>
        <p class="df-placeholder-desc muted">{{ t('design.flowPlaceholderDesc') }}</p>
        <div class="df-tag">
          <el-icon><Clock /></el-icon>
          <span>{{ t('design.flowPlannedLater') }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Share, Clock } from '@element-plus/icons-vue';
import { useI18n } from 'vue-i18n';

const { t } = useI18n();
</script>

<style scoped>
.df-page .page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 24px;
}
.df-page .page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; }
.df-page .page-desc  { margin: 4px 0 0; font-size: 13px; }

.df-placeholder {
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-elev);
  padding: 72px 24px;
}
.df-placeholder-inner {
  max-width: 480px;
  margin: 0 auto;
  text-align: center;
}
.df-placeholder-icon {
  font-size: 44px;
  color: var(--color-text-muted);
  margin-bottom: 16px;
}
.df-placeholder-title {
  margin: 0 0 6px;
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text);
}
.df-placeholder-desc {
  margin: 0 0 24px;
  font-size: 13px;
  line-height: 1.6;
}
.df-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 4px 12px;
  border-radius: 12px;
  background: var(--color-primary-soft);
  color: var(--color-primary);
  font-weight: 500;
}
</style>