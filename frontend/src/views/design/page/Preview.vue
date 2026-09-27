<template>
  <div class="page-container fade-in-up dp-preview-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ page?.name || '…' }}</h1>
        <p class="page-desc muted">
          {{ t('design.previewDesc') }}
          <span v-if="page" class="muted">· {{ page.code }}</span>
        </p>
      </div>
      <div class="header-actions">
        <el-button v-if="page" :icon="ArrowLeft" @click="goBack">{{ t('design.backToEdit') }}</el-button>
        <el-button :icon="ArrowLeft" @click="goList">{{ t('design.backToList') }}</el-button>
      </div>
    </div>

    <div v-if="loading" class="muted">{{ t('common.reload') }}…</div>

    <div v-else-if="rootNode" class="dp-preview-canvas" :style="canvasStyle">
      <template v-if="(rootNode.children || []).length === 0">
        <div class="dp-preview-empty muted">{{ t('design.canvasEmpty') }}</div>
      </template>
      <div v-for="child in rootNode.children || []" :key="child.id" class="dp-preview-block">
        <DynamicRenderer :node="child" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useI18n } from 'vue-i18n';
import { ArrowLeft } from '@element-plus/icons-vue';
import { portalPageApi, type PortalPage } from '@/api/portalPage';
import DynamicRenderer from '@/components/portal/DynamicRenderer.vue';

const props = defineProps<{ id: string }>();
const { t } = useI18n();
const route = useRoute();
const router = useRouter();

const page = ref<PortalPage | null>(null);
const loading = ref(false);

const rootNode = computed(() => {
  if (!page.value) return null;
  try {
    const parsed = JSON.parse(page.value.layoutSchema);
    return parsed.root || parsed;
  } catch {
    return null;
  }
});

const canvasStyle = computed(() => ({
  padding: (rootNode.value?.props?.padding ?? 24) + 'px',
  gap: (rootNode.value?.props?.gap ?? 16) + 'px',
}));

function goBack() {
  if (page.value) router.push(`/design/page/${page.value.id}/edit`);
}
function goList() {
  router.push('/design/page');
}

onMounted(async () => {
  const id = Number(props.id);
  if (!id || isNaN(id)) {
    ElMessage.error('Invalid page id');
    router.push('/design/page');
    return;
  }
  loading.value = true;
  try {
    page.value = await portalPageApi.detail(id);
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  } finally {
    loading.value = false;
  }
});
</script>

<style scoped>
.dp-preview-page .page-header {
  display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 24px;
}
.dp-preview-page .page-title { margin: 0; font-size: 22px; font-weight: 700; }
.dp-preview-page .page-desc  { margin: 4px 0 0; font-size: 13px; }
.header-actions { display: inline-flex; gap: 8px; }

.dp-preview-canvas {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg);
  min-height: 240px;
}
.dp-preview-block {
  border-radius: var(--radius-xs);
}
.dp-preview-empty {
  padding: 80px 24px;
  text-align: center;
  font-size: 13px;
}
</style>