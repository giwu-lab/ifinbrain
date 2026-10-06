<template>
  <div class="page-container fade-in-up form-preview-page">
    <div class="page-header">
      <div>
        <el-button link :icon="ArrowLeft" @click="goBack">{{ t('design.formBack') }}</el-button>
        <h1 class="page-title">{{ meta?.name || t('design.formUntitled') }}</h1>
        <p class="page-desc muted">
          <code class="mono">{{ meta?.code }}</code>
          <span class="status-block" :class="meta?.status === 1 ? 'on' : 'off'">
            {{ meta?.status === 1 ? t('common.enabled') : t('common.disabled') }}
          </span>
        </p>
      </div>
      <div class="header-actions">
        <el-button
          v-if="auth.hasPerm('design:form:update')"
          type="primary"
          :icon="Edit"
          @click="goEdit"
        >{{ t('design.formEdit') }}</el-button>
      </div>
    </div>

    <div class="preview-wrap">
      <DynamicFormRenderer
        v-if="meta"
        :fields="meta.fields"
        :model-value="formData"
        @update:model-value="(v) => formData = v"
      />
      <div v-else class="loading muted">{{ t('common.loading') }}</div>
    </div>

    <div v-if="meta" class="data-preview">
      <div class="data-title muted">{{ t('design.formPreviewData') }}</div>
      <pre class="data-json mono">{{ JSON.stringify(formData, null, 2) }}</pre>
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
import { formDesignApi, type FormDesignItem } from '@/api/formDesign';
import DynamicFormRenderer from '@/components/form/FormRenderer.vue';

const props = defineProps<{ id?: string }>();
const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const meta = ref<FormDesignItem | null>(null);
const formData = ref<Record<string, any>>({});

function goBack() {
  router.push('/design/form');
}

function goEdit() {
  if (meta.value?.id) router.push(`/design/form/${meta.value.id}/edit`);
}

onMounted(async () => {
  if (!props.id) {
    router.push('/design/form');
    return;
  }
  try {
    const item = await formDesignApi.detail(Number(props.id));
    meta.value = item;
    // 用每个字段的 defaultValue 预填
    const init: Record<string, any> = {};
    for (const f of item.fields) {
      if (f.defaultValue != null && f.defaultValue !== '') {
        init[f.key] = f.type === 'NUMBER' ? Number(f.defaultValue) : f.defaultValue;
      }
    }
    formData.value = init;
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
    router.push('/design/form');
  }
});
</script>

<style scoped>
.form-preview-page .page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 16px;
}
.form-preview-page .page-title { margin: 4px 0 4px; font-size: 22px; font-weight: 700; }
.form-preview-page .page-desc  { margin: 0; font-size: 13px; display: flex; align-items: center; gap: 8px; }
.status-block {
  font-size: 11px; padding: 1px 8px; border-radius: 2px; font-weight: 500;
}
.status-block.on  { background: #e6f4ea; color: #0e8f5c; }
.status-block.off { background: #f0f0eb; color: #8a8a85; }

.preview-wrap {
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-elev);
  min-height: 200px;
}
.loading { padding: 40px 0; text-align: center; }

.data-preview {
  margin-top: 16px;
  padding: 16px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-elev);
}
.data-title { font-size: 11px; text-transform: uppercase; letter-spacing: 0.04em; margin-bottom: 8px; }
.data-json {
  margin: 0;
  padding: 12px;
  font-size: 12px;
  background: var(--color-bg);
  border: 1px solid var(--color-border-soft);
  border-radius: var(--radius-xs);
  max-height: 240px;
  overflow: auto;
  font-family: 'SF Mono', 'JetBrains Mono', Menlo, monospace;
}
</style>