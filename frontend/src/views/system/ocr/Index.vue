<template>
  <div class="page-container fade-in-up">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('system.parserTitle') }}</h1>
        <p class="page-desc muted">{{ t('system.parserTitleDesc') }}</p>
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
      <template #title>{{ t('system.parserBannerTitle') }}</template>
      <template #default>{{ t('system.parserBannerDesc') }}</template>
    </el-alert>

    <el-row :gutter="20">
      <el-col :md="24" :lg="14">
        <el-card class="card-table" shadow="never">
          <el-form :model="form" label-width="160px">
            <el-form-item :label="t('system.parserEnabledLabel')">
              <el-switch
                v-model="form.enabled"
                :active-text="t('system.parserEnabledOn')"
                :inactive-text="t('system.parserEnabledOff')"
                inline-prompt
              />
              <span class="form-hint">{{ t('system.parserEnabledHint') }}</span>
            </el-form-item>

            <el-divider content-position="left">
              <span class="divider-text">
                <el-icon><MagicStick /></el-icon>
                {{ t('system.parserEndpointSection') }}
              </span>
            </el-divider>

            <el-form-item :label="t('system.parserBaseUrlLabel')">
              <el-input
                v-model="form.baseUrl"
                :disabled="!form.enabled"
                :placeholder="t('system.parserBaseUrlPlaceholder')"
                clearable
              >
                <template #prefix><el-icon><Link /></el-icon></template>
              </el-input>
            </el-form-item>

            <el-form-item :label="t('system.parserApiKeyLabel')">
              <div class="api-key-row">
                <template v-if="hadKey && !editingKey">
                  <el-tag type="success" effect="light" round>
                    <el-icon style="margin-right:4px;"><CircleCheckFilled /></el-icon>
                    {{ t('system.parserApiKeyConfigured') }}
                  </el-tag>
                  <el-button link type="primary" size="small" @click="startEditKey">
                    {{ t('system.parserApiKeyChange') }}
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
                <span class="form-hint">{{ t('system.parserApiKeyHint') }}</span>
              </div>
            </el-form-item>

            <el-form-item :label="t('system.parserTimeoutLabel')">
              <el-input-number
                v-model="form.timeoutSeconds"
                :disabled="!form.enabled"
                :min="5"
                :max="600"
                :step="5"
              />
              <span class="form-hint">{{ t('system.parserTimeoutUnit') }}</span>
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
                <span style="margin-left: 4px;">{{ t('system.parserTestBtn') }}</span>
              </el-button>
              <span v-if="lastTestUrl" class="test-url">{{ lastTestUrl }}</span>
            </el-form-item>

            <!-- 高级选项：endpoint 路径可配，对接第三方 OCR / 自定义服务时改 -->
            <el-collapse v-model="advancedOpen" class="advanced-collapse">
              <el-collapse-item :title="t('system.parserAdvanced')" name="adv">
                <el-form-item :label="t('system.parserExtractPathLabel')">
                  <el-input
                    v-model="form.extractPath"
                    :disabled="!form.enabled"
                    :placeholder="t('system.parserExtractPathPlaceholder')"
                    clearable
                  />
                </el-form-item>
                <el-form-item :label="t('system.parserBatchPathLabel')">
                  <el-input
                    v-model="form.batchPath"
                    :disabled="!form.enabled"
                    :placeholder="t('system.parserBatchPathPlaceholder')"
                    clearable
                  />
                </el-form-item>
                <el-form-item :label="t('system.parserHealthPathLabel')">
                  <el-input
                    v-model="form.healthPath"
                    :disabled="!form.enabled"
                    :placeholder="t('system.parserHealthPathPlaceholder')"
                    clearable
                  />
                  <span class="form-hint">{{ t('system.parserPathHint') }}</span>
                </el-form-item>
              </el-collapse-item>
            </el-collapse>
          </el-form>
        </el-card>

        <!-- 预留：未来扩展 OCR API 等其他解析服务 -->
        <el-card class="card-table reserved-card" shadow="never">
          <template #header>
            <span class="card-title">
              <el-icon class="reserved-tag"><Star /></el-icon>
              {{ t('system.parserReservedTitle') }}
            </span>
          </template>
          <p class="reserved-desc">{{ t('system.parserReservedDesc') }}</p>
          <div class="reserved-grid">
            <div class="reserved-item">
              <el-icon><PictureFilled /></el-icon>
              <div>
                <strong>{{ t('system.parserOcrPlaceholderTitle') }}</strong>
                <p>{{ t('system.parserOcrPlaceholderDesc') }}</p>
              </div>
              <el-tag size="small" type="info" effect="plain" round>{{ t('system.parserOcrPlaceholderStatus') }}</el-tag>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :md="24" :lg="10">
        <el-card class="card-table status-card" shadow="never">
          <template #header>
            <span class="card-title">{{ t('system.parserStatusTitle') }}</span>
          </template>
          <div class="status-display">
            <div class="big-icon" :class="form.enabled ? 'on' : 'off'">
              <el-icon><MagicStick /></el-icon>
            </div>
            <h2 class="status-name">
              {{ form.enabled ? t('system.parserStatusOn') : t('system.parserStatusOff') }}
            </h2>
            <p class="status-detail">
              <template v-if="form.enabled">
                {{ form.baseUrl || '—' }}
              </template>
              <template v-else>
                {{ t('system.parserStatusOffDesc') }}
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
import { MagicStick, Link, Connection, CircleCheckFilled, Star, PictureFilled } from '@element-plus/icons-vue';
import { settingApi, type ParserConfig } from '@/api/setting';

const { t } = useI18n();
const loading = ref(false);
const saving = ref(false);
const testing = ref(false);
const hadKey = ref(false);
const editingKey = ref(false);
const advancedOpen = ref<string[]>([]);
const lastTestUrl = ref('');

const form = reactive<ParserConfig>({
  enabled: false,
  baseUrl: '',
  apiKey: '',
  timeoutSeconds: 60,
  extractPath: '/extract',
  batchPath: '/extract/batch',
  healthPath: '/health',
});

const apiKeyPlaceholder = computed(() =>
  hadKey.value ? t('system.parserApiKeyKeepPlaceholder') : t('system.parserApiKeyPlaceholder'),
);

async function reload() {
  loading.value = true;
  try {
    const r = await settingApi.parser();
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
    const payload: ParserConfig = { ...form };
    if (!editingKey.value) payload.apiKey = '';
    await settingApi.saveParser(payload);
    ElMessage.success(t('system.parserSaved'));
    await reload();
  } finally {
    saving.value = false;
  }
}

async function testConnection() {
  testing.value = true;
  lastTestUrl.value = '';
  try {
    const result = await settingApi.testParser({ baseUrl: form.baseUrl });
    lastTestUrl.value = result.url || '';
    if (result && result.ok) {
      ElMessage.success(t('system.parserTestOk'));
    } else {
      ElMessage.warning(t('system.parserTestFail'));
    }
  } catch {
    ElMessage.error(t('system.parserTestFail'));
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
.test-url { margin-left: 12px; font-size: 12px; color: var(--color-text-muted); font-family: 'SF Mono', Menlo, monospace; }

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

.advanced-collapse { margin-top: 16px; }
:deep(.el-collapse-item__header) {
  font-weight: 500;
  color: var(--color-text-secondary);
}

.reserved-card { margin-top: 16px; }
.reserved-tag { color: var(--color-warning); margin-right: 6px; vertical-align: -2px; }
.reserved-desc {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--color-text-secondary);
}
.reserved-grid { display: flex; flex-direction: column; gap: 10px; }
.reserved-item {
  display: grid;
  grid-template-columns: 32px 1fr auto;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-bg);
  color: var(--color-text-secondary);
}
.reserved-item .el-icon { font-size: 20px; color: var(--color-info); }
.reserved-item strong { color: var(--color-text); font-weight: 600; }
.reserved-item p { margin: 2px 0 0; font-size: 12px; }
</style>