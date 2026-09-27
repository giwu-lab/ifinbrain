<template>
  <div class="portal-page fade-in-up">
    <div v-if="loading" class="portal-loading muted">{{ t('common.reload') }}…</div>

    <template v-else-if="resolved && rootNode">
      <div class="portal-canvas" :style="canvasStyle">
        <div v-for="child in rootNode.children || []" :key="child.id" class="portal-block">
          <DynamicRenderer :node="child" />
        </div>
      </div>
    </template>

    <div v-else class="portal-empty">
      <el-icon class="empty-icon"><Files /></el-icon>
      <h3>{{ t('design.portalNoBinding') }}</h3>
      <p class="muted">{{ t('design.portalNoBindingDesc') }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useI18n } from 'vue-i18n';
import { Files } from '@element-plus/icons-vue';
import { portalPageApi, type PortalResolveResp } from '@/api/portalPage';
import DynamicRenderer from '@/components/portal/DynamicRenderer.vue';

const { t } = useI18n();

const resolved = ref<PortalResolveResp | null>(null);
const loading = ref(false);

const rootNode = computed(() => {
  if (!resolved.value?.layoutSchema) return null;
  try {
    const parsed = JSON.parse(resolved.value.layoutSchema);
    return parsed.root || parsed;
  } catch { return null; }
});

const canvasStyle = computed(() => ({
  padding: (rootNode.value?.props?.padding ?? 24) + 'px',
  gap: (rootNode.value?.props?.gap ?? 16) + 'px',
}));

onMounted(async () => {
  loading.value = true;
  try {
    resolved.value = await portalPageApi.resolve();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  } finally {
    loading.value = false;
  }
});
</script>

<style scoped>
.portal-page { padding: 24px; min-height: 100%; }
.portal-loading { padding: 48px; text-align: center; }

.portal-canvas {
  display: flex;
  flex-direction: column;
  border-radius: var(--radius-sm);
}
.portal-empty {
  padding: 96px 24px;
  text-align: center;
}
.empty-icon { font-size: 48px; color: var(--color-text-muted); margin-bottom: 12px; }
</style>