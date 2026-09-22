<template>
  <div class="page-container fade-in-up">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('system.ocrTitle') }}</h1>
        <p class="page-desc muted">{{ t('system.ocrTitleDesc') }}</p>
      </div>
      <div class="header-actions">
        <el-button @click="reload" :loading="loading">{{ t('common.reload') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('common.save') }}</el-button>
      </div>
    </div>

    <el-alert
      v-if="!form.enabled"
      type="warning"
      :closable="false"
      show-icon
      class="banner"
    >
      <template #title>
        {{ t('system.ocrBannerTitle') }}
      </template>
      <template #default>
        {{ t('system.ocrBannerDesc') }}
      </template>
    </el-alert>

    <el-row :gutter="20">
      <el-col :md="24" :lg="14">
        <el-card class="card-table" shadow="never">
          <el-form :model="form" label-width="160px">
            <el-form-item :label="t('system.ocrEnabledLabel')">
              <el-switch
                v-model="form.enabled"
                :active-text="t('system.ocrEnabledOn')"
                :inactive-text="t('system.ocrEnabledOff')"
                inline-prompt
              />
              <span class="form-hint">{{ t('system.ocrEnabledHint') }}</span>
            </el-form-item>

            <el-divider content-position="left">
              <span class="divider-text">
                <el-icon><MagicStick /></el-icon>
                {{ t('system.ocrEndpointSection') }}
              </span>
            </el-divider>

            <el-form-item :label="t('system.ocrBaseUrlLabel')">
              <el-input
                v-model="form.baseUrl"
                :disabled="!form.enabled"
                placeholder="http://localhost:8091"
                clearable
              >
                <template #prefix><el-icon><Link /></el-icon></template>
              </el-input>
            </el-form-item>

            <el-form-item :label="t('system.ocrApiKeyLabel')">
              <div class="api-key-row">
                <template v-if="hadKey && !editingKey">
                  <el-tag type="success" effect="light" round>
                    <el-icon style="margin-right:4px;"><CircleCheckFilled /></el-icon>
                    {{ t('system.ocrApiKeyConfigured') }}
                  </el-tag>
                  <el-button link type="primary" size="small" @click="startEditKey">
                    {{ t('system.ocrApiKeyChange') }}
                  </el-button>
                </template>
                <el-input
                  v-else
                  v-model="form.apiKey"
                  type="password"
                  show-password
                  :disabled="!form.enabled"
                  :placeholder="apiKeyPlaceholder"
                  clearable
                />
                <span class="form-hint">{{ t('system.ocrApiKeyHint') }}</span>
              </div>
            </el-form-item>

            <el-form-item :label="t('system.ocrTimeoutLabel')">
              <el-input-number
                v-model="form.timeoutSeconds"
                :disabled="!form.enabled"
                :min="5"
                :max="600"
                :step="5"
              />
              <span class="form-hint">s</span>
            </el-form-item>

            <el-form-item>
              <el-button
                type="warning"
                plain
                :loading="testing"
                :disabled="!form.enabled || !form.baseUrl"
                @click="testConnection"
              >
                <el-icon><Connection /></el-icon>
                <span style="margin-left: 4px;">{{ t('system.ocrTestBtn') }}</span>
              </el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :md="24" :lg="10">
        <el-card class="card-table status-card" shadow="never">
          <template #header>
            <span class="card-title">{{ t('system.ocrStatusTitle') }}</span>
          </template>
          <div class="status-display">
            <div class="big-icon" :class="form.enabled ? 'on' : 'off'">
              <el-icon><MagicStick /></el-icon>
            </div>
            <h2 class="status-name">
              {{ form.enabled ? t('system.ocrStatusOn') : t('system.ocrStatusOff') }}
            </h2>
            <p class="status-detail">
              <template v-if="form.enabled">
                {{ form.baseUrl || '—' }}
              </template>
              <template v-else>
                {{ t('system.ocrStatusOffDesc') }}
              </template>
            </p>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { MagicStick, Link, Connection, CircleCheckFilled } from '@element-plus/icons-vue';
import { settingApi, type OcrConfig } from '@/api/setting';

const { t } = useI18n();
const loading = ref(false);
const saving = ref(false);
const testing = ref(false);
const hadKey = ref(false);
const editingKey = ref(false);

const form = reactive<OcrConfig>({
  enabled: false,
  baseUrl: '',
  apiKey: '',
  timeoutSeconds: 60,
});

const apiKeyPlaceholder = computed(() =>
  hadKey.value ? t('system.ocrApiKeyKeepPlaceholder') : t('system.ocrApiKeyPlaceholder'),
);

async function reload() {
  loading.value = true;
  try {
    const r = await settingApi.ocr();
    Object.assign(form, r);
    hadKey.value = !!(r.apiKey && r.apiKey.length > 0);
    form.apiKey = '';
    editingKey.value = false;
  } finally {
    loading.value = false;
  }
}

function startEditKey() {
  editingKey.value = true;
  form.apiKey = '';
}

async function save() {
  saving.value = true;
  try {
    // 当未修改 key 时，不要把空字符串发上去——空串会被当作「清空」。
    const payload: OcrConfig = { ...form };
    if (!editingKey.value) payload.apiKey = '';
    await settingApi.saveOcr(payload);
    ElMessage.success(t('system.ocrSaved'));
    await reload();
  } finally {
    saving.value = false;
  }
}

async function testConnection() {
  testing.value = true;
  try {
    const result = await settingApi.testOcr({ baseUrl: form.baseUrl });
    if (result && result.ok) {
      ElMessage.success(t('system.ocrTestOk'));
    } else {
      ElMessage.warning(t('system.ocrTestFail'));
    }
  } catch {
    ElMessage.error(t('system.ocrTestFail'));
  } finally {
    testing.value = false;
  }
}

onMounted(reload);
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 20px; }
.page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; }
.page-desc { margin: 4px 0 0; font-size: 13px; }
.header-actions { display: flex; gap: 8px; }
.divider-text { display: inline-flex; align-items: center; gap: 6px; color: var(--color-text-secondary); font-weight: 500; }
.card-title { font-weight: 600; }
.form-hint { margin-left: 12px; font-size: 12px; color: var(--color-text-muted); }
.api-key-row { display: inline-flex; align-items: center; gap: 12px; flex-wrap: wrap; }

.status-card { display: flex; flex-direction: column; }
.banner { margin-bottom: 16px; }
.status-display {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 24px 16px 8px;
  text-align: center;
}
.big-icon {
  width: 72px; height: 72px;
  border-radius: 18px;
  display: flex; align-items: center; justify-content: center;
  font-size: 36px;
  color: white;
  margin-bottom: 16px;
}
.big-icon.on  { background: linear-gradient(135deg, #6366f1, #3b82f6); }
.big-icon.off { background: linear-gradient(135deg, #9ca3af, #6b7280); }
.status-name { margin: 0; font-size: 18px; font-weight: 700; }
.status-detail { margin: 8px 0 0; font-size: 13px; color: var(--color-text-secondary); word-break: break-all; }
</style>