<template>
  <div class="page-container fade-in-up flow-preview-page">
    <div class="page-header">
      <div>
        <el-button link :icon="ArrowLeft" @click="goBack">{{ t('design.flowBack') }}</el-button>
        <h1 class="page-title">{{ meta?.name || t('design.flowUntitled') }}</h1>
        <p class="page-desc muted">
          <code class="mono">{{ meta?.code }}</code>
          <span class="status-block" :class="meta?.status === 1 ? 'on' : 'off'">
            {{ meta?.status === 1 ? t('common.enabled') : t('common.disabled') }}
          </span>
          <span class="muted small">v{{ meta?.version }} · {{ meta?.nodeCount }} 节点 / {{ meta?.edgeCount }} 连线</span>
        </p>
      </div>
      <div class="header-actions">
        <el-button
          v-if="auth.hasPerm('design:flow:update')"
          type="primary"
          :icon="Edit"
          @click="goEdit"
        >{{ t('design.flowEdit') }}</el-button>
      </div>
    </div>

    <div class="preview-wrap">
      <BpmnCanvas v-if="loaded" />
      <div v-else class="loading muted">{{ t('common.loading') }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useI18n } from 'vue-i18n';
import { ArrowLeft, Edit } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { flowDefineApi, type FlowDefineItem } from '@/api/flowDefine';
import { useFlowDesignStore } from '@/stores/flowDesign';
import BpmnCanvas from '@/components/flow/BpmnCanvas.vue';

const props = defineProps<{ id?: string }>();
const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const store = useFlowDesignStore();

const meta = ref<FlowDefineItem | null>(null);
const loaded = ref(false);

function goBack() { router.push('/design/flow'); }
function goEdit() {
  if (meta.value?.id) router.push(`/design/flow/${meta.value.id}/edit`);
}

onMounted(async () => {
  if (!props.id) { router.push('/design/flow'); return; }
  try {
    const item = await flowDefineApi.detail(Number(props.id));
    meta.value = item;
    store.reset(item);
    loaded.value = true;
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
    router.push('/design/flow');
  }
});
</script>

<style scoped>
.flow-preview-page .page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 8px;
}
.flow-preview-page .page-title { margin: 4px 0 4px; font-size: 22px; font-weight: 700; }
.flow-preview-page .page-desc  { margin: 0; font-size: 13px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.status-block {
  font-size: 11px; padding: 1px 8px; border-radius: 2px; font-weight: 500;
}
.status-block.on  { background: #e6f4ea; color: #0e8f5c; }
.status-block.off { background: #f0f0eb; color: #8a8a85; }

.preview-wrap {
  height: calc(100vh - 64px - 100px - 32px);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-elev);
  overflow: hidden;
}
.loading { padding: 40px 0; text-align: center; }
</style>