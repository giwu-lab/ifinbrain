<template>
  <div class="page-container fade-in-up">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('system.storageTitle') }}</h1>
        <p class="page-desc muted">{{ t('system.storageTitleDesc') }}</p>
      </div>
      <div class="header-actions">
        <el-button @click="reload" :loading="loading">{{ t('common.reload') }}</el-button>
        <el-button
          v-if="form.type === 's3'"
          type="warning" plain
          :loading="testing"
          :disabled="!canTest"
          @click="testConnection"
        >
          <el-icon><Connection /></el-icon>
          <span class="btn-label">{{ t('system.parserTestBtn') }}</span>
        </el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('common.save') }}</el-button>
      </div>
    </div>

    <el-row :gutter="20">
      <el-col :md="24" :lg="14">
        <el-card class="card-table" shadow="never">
          <el-form :model="form" label-width="160px">            <el-form-item :label="t('system.storageTypeLabel')">
              <el-select v-model="form.type" style="width: 240px;">
                <el-option value="local">
                  <span style="display: inline-flex; align-items: center; gap: 6px;">
                    <el-icon><Folder /></el-icon>
                    {{ t('system.storageTypeLocal') }}
                  </span>
                </el-option>
                <el-option value="s3">
                  <span style="display: inline-flex; align-items: center; gap: 6px;">
                    <el-icon><Coin /></el-icon>
                    {{ t('system.storageTypeS3') }}
                  </span>
                </el-option>
              </el-select>
            </el-form-item>

            <template v-if="form.type === 'local'">
              <el-divider content-position="left">
                <span class="divider-text">
                  <el-icon><Folder /></el-icon>
                  {{ t('system.localDiskSection') }}
                </span>
              </el-divider>
              <el-form-item :label="t('system.rootPathLabel')">
                <el-input
                  v-model="form.local.root"
                  placeholder="./data/storage"
                >
                  <template #prefix><el-icon><FolderOpened /></el-icon></template>
                </el-input>
              </el-form-item>
            </template>

            <template v-if="form.type === 's3'">
              <el-divider content-position="left">
                <span class="divider-text">
                  <el-icon><Coin /></el-icon>
                  {{ t('system.s3ConfigSection') }}
                </span>
              </el-divider>
              <el-form-item :label="t('system.s3EndpointLabel')">
                <el-input v-model="form.s3.endpoint" placeholder="http://minio:9000" />
              </el-form-item>
              <el-form-item :label="t('system.s3RegionLabel')">
                <el-input v-model="form.s3.region" placeholder="us-east-1" />
              </el-form-item>
              <el-form-item :label="t('system.s3BucketLabel')">
                <el-input v-model="form.s3.bucket" />
              </el-form-item>

              <!-- Access Key：三态（参考解析页 API Key） -->
              <el-form-item :label="t('system.s3AccessKeyLabel')" class="key-item">
                <div class="key-row">
                  <template v-if="accessKeyStatus !== 'missing' && !editingAccessKey">
                    <el-tag
                      :type="accessKeyStatus === 'failed' ? 'warning' : 'success'"
                      effect="light" round class="key-state"
                    >
                      <el-icon style="margin-right:4px;">
                        <component :is="accessKeyStatus === 'failed' ? WarningFilled : CircleCheckFilled" />
                      </el-icon>
                      {{ accessKeyStatus === 'failed' ? t('system.s3AccessKeyFailed') : t('system.s3AccessKeyConfigured') }}
                    </el-tag>
                    <el-button link type="primary" size="small" @click="startEditAccessKey">
                      {{ t('system.s3AccessKeyChange') }}
                    </el-button>
                  </template>
                  <el-input
                    v-else
                    ref="accessKeyInputRef"
                    v-model="form.s3.accessKey"
                    type="password" show-password
                    :placeholder="accessKeyPlaceholder"
                    clearable
                    @focus="clearAccessKeyOnFocus"
                  />
                </div>
              </el-form-item>

              <!-- Secret Key：三态 -->
              <el-form-item :label="t('system.s3SecretKeyLabel')" class="key-item">
                <div class="key-row">
                  <template v-if="secretKeyStatus !== 'missing' && !editingSecretKey">
                    <el-tag
                      :type="secretKeyStatus === 'failed' ? 'warning' : 'success'"
                      effect="light" round class="key-state"
                    >
                      <el-icon style="margin-right:4px;">
                        <component :is="secretKeyStatus === 'failed' ? WarningFilled : CircleCheckFilled" />
                      </el-icon>
                      {{ secretKeyStatus === 'failed' ? t('system.s3SecretKeyFailed') : t('system.s3SecretKeyConfigured') }}
                    </el-tag>
                    <el-button link type="primary" size="small" @click="startEditSecretKey">
                      {{ t('system.s3SecretKeyChange') }}
                    </el-button>
                  </template>
                  <el-input
                    v-else
                    ref="secretKeyInputRef"
                    v-model="form.s3.secretKey"
                    type="password" show-password
                    :placeholder="secretKeyPlaceholder"
                    clearable
                    @focus="clearSecretKeyOnFocus"
                  />
                </div>
              </el-form-item>

              <el-form-item :label="t('system.s3PathStyleLabel')">
                <el-switch v-model="form.s3.pathStyle" />
              </el-form-item>
            </template>
          </el-form>
        </el-card>
      </el-col>

      <el-col :md="24" :lg="10">
        <el-card class="card-table status-card" shadow="never">
          <template #header>
            <span class="card-title">{{ t('dashboard.storageType') }}</span>
          </template>
          <div class="status-display">
            <div class="big-icon" :class="form.type === 'local' ? 'local' : 's3'">
              <el-icon><Folder v-if="form.type === 'local'" /><Coin v-else /></el-icon>
            </div>
            <h2 class="status-name">{{ form.type === 'local' ? t('system.storageTypeLocal') : t('system.storageTypeS3') }}</h2>
            <p class="status-detail">
              <template v-if="form.type === 'local'">
                {{ form.local.root || './data/storage' }}
              </template>
              <template v-else>
                {{ form.s3.endpoint || '—' }} / {{ form.s3.bucket || '—' }}
              </template>
            </p>

            <!-- S3 密钥状态：参考解析页右侧 aside -->
            <template v-if="form.type === 's3'">
              <div class="key-status-row">
                <span class="key-status-label">{{ t('system.s3AccessKeyLabel') }}</span>
                <span class="key-status-value" :class="accessKeyValueClass">
                  <template v-if="accessKeyStatus === 'ok'">
                    <el-icon class="key-dot"><CircleCheckFilled /></el-icon>
                    {{ t('system.s3AccessKeyConfigured') }}
                  </template>
                  <template v-else-if="accessKeyStatus === 'failed'">
                    <el-icon class="key-dot key-dot-warn"><WarningFilled /></el-icon>
                    {{ t('system.s3AccessKeyFailed') }}
                  </template>
                  <template v-else>
                    <span class="muted">—</span>
                  </template>
                </span>
              </div>
              <div class="key-status-row">
                <span class="key-status-label">{{ t('system.s3SecretKeyLabel') }}</span>
                <span class="key-status-value" :class="secretKeyValueClass">
                  <template v-if="secretKeyStatus === 'ok'">
                    <el-icon class="key-dot"><CircleCheckFilled /></el-icon>
                    {{ t('system.s3SecretKeyConfigured') }}
                  </template>
                  <template v-else-if="secretKeyStatus === 'failed'">
                    <el-icon class="key-dot key-dot-warn"><WarningFilled /></el-icon>
                    {{ t('system.s3SecretKeyFailed') }}
                  </template>
                  <template v-else>
                    <span class="muted">—</span>
                  </template>
                </span>
              </div>
              <div v-if="anySecretFailed" class="status-foot status-foot-warn">
                {{ t('system.s3SecretFailedHint') }}
              </div>
            </template>

            <!-- 测试连通结果 -->
            <div v-if="form.type === 's3' && lastTest" class="test-result" :class="lastTest.ok ? 'ok' : 'fail'">
              <el-icon class="result-icon">
                <component :is="lastTest.ok ? CircleCheckFilled : WarningFilled" />
              </el-icon>
              <div class="result-body">
                <div class="result-line">{{ lastTest.ok ? t('system.testOk') : t('system.testFail') }}</div>
                <div class="result-detail">{{ lastTest.message }} · {{ lastTest.latencyMs }} ms</div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { Folder, FolderOpened, Coin, Connection, CircleCheckFilled, WarningFilled } from '@element-plus/icons-vue';
import { settingApi, type StorageConfig } from '@/api/setting';

const { t } = useI18n();
const loading = ref(false);
const saving = ref(false);
const testing = ref(false);
const lastTest = ref<{ ok: boolean; message: string; latencyMs: number; bucket: string } | null>(null);
const form = reactive<StorageConfig>({
  type: 'local',
  local: { root: './data/storage' },
  s3: { endpoint: '', region: 'us-east-1', bucket: '', accessKey: '', secretKey: '', pathStyle: true }
});

// 三态：missing（未存）/ ok（已存且能解密）/ failed（已存但解密失败）
const accessKeyStatus = ref<'missing' | 'ok' | 'failed'>('missing');
const secretKeyStatus = ref<'missing' | 'ok' | 'failed'>('missing');
const editingAccessKey = ref(false);
const editingSecretKey = ref(false);
const accessKeyInputRef = ref();
const secretKeyInputRef = ref();

const accessKeyValueClass = computed(() => {
  if (accessKeyStatus.value === 'ok') return 'on';
  if (accessKeyStatus.value === 'failed') return 'warn';
  return 'off';
});
const secretKeyValueClass = computed(() => {
  if (secretKeyStatus.value === 'ok') return 'on';
  if (secretKeyStatus.value === 'failed') return 'warn';
  return 'off';
});
const anySecretFailed = computed(() =>
  accessKeyStatus.value === 'failed' || secretKeyStatus.value === 'failed'
);

const accessKeyPlaceholder = computed(() => {
  // 在编辑态下：曾有 ok 历史 → 「留空表示不变」；首次配置 → 「输入新的」
  if (editingAccessKey.value && accessKeyStatus.value === 'ok') {
    return t('system.s3AccessKeyKeepPlaceholder');
  }
  return t('system.s3AccessKeyPlaceholder');
});
const secretKeyPlaceholder = computed(() => {
  if (editingSecretKey.value && secretKeyStatus.value === 'ok') {
    return t('system.s3SecretKeyKeepPlaceholder');
  }
  return t('system.s3SecretKeyPlaceholder');
});

/**
 * 测试连通：在编辑态下接受新输入；非编辑态接受已存（status=ok）。
 * failed 状态视作「无可用密钥」，必须先重新填写才能测。
 */
function hasUsableKey(editing: boolean, val: string, status: 'missing' | 'ok' | 'failed'): boolean {
  if (editing && val && val !== '********') return true;
  return status === 'ok';
}
const canTest = computed(() => {
  if (form.type !== 's3') return false;
  return !!(
    form.s3.endpoint && form.s3.bucket &&
    hasUsableKey(editingAccessKey.value, form.s3.accessKey, accessKeyStatus.value) &&
    hasUsableKey(editingSecretKey.value, form.s3.secretKey, secretKeyStatus.value)
  );
});

async function reload() {
  loading.value = true;
  lastTest.value = null;
  try {
    const r = await settingApi.storage();
    Object.assign(form, r);
    // reload 后表单上的 AK/SK 清空，靠 status 字段渲染状态卡 / 测试按钮
    form.s3.accessKey = '';
    form.s3.secretKey = '';
    accessKeyStatus.value = r.s3.accessKeyStatus ?? (r.s3.accessKey ? 'ok' : 'missing');
    secretKeyStatus.value = r.s3.secretKeyStatus ?? (r.s3.secretKey ? 'ok' : 'missing');
    editingAccessKey.value = false;
    editingSecretKey.value = false;
  } finally { loading.value = false; }
}

function startEditAccessKey() {
  editingAccessKey.value = true;
  // ok 时预填掩码（"这里有东西需要先删再改"），failed 时旧值不可用 → 直接空，让用户输入新 key
  form.s3.accessKey = accessKeyStatus.value === 'ok' ? '********' : '';
  nextTick(() => {
    accessKeyInputRef.value?.focus();
    if (form.s3.accessKey === '********') form.s3.accessKey = '';
  });
}
function clearAccessKeyOnFocus() {
  if (form.s3.accessKey === '********') form.s3.accessKey = '';
}
function startEditSecretKey() {
  editingSecretKey.value = true;
  form.s3.secretKey = secretKeyStatus.value === 'ok' ? '********' : '';
  nextTick(() => {
    secretKeyInputRef.value?.focus();
    if (form.s3.secretKey === '********') form.s3.secretKey = '';
  });
}
function clearSecretKeyOnFocus() {
  if (form.s3.secretKey === '********') form.s3.secretKey = '';
}

async function save() {
  saving.value = true;
  try {
    const payload: StorageConfig = JSON.parse(JSON.stringify(form));
    // 非编辑态的 AK/SK：留空 → 后端视为「保留旧值」（参考 saveStorageConfig saveSecretIfPresent）
    if (!editingAccessKey.value) payload.s3.accessKey = '';
    if (!editingSecretKey.value) payload.s3.secretKey = '';
    await settingApi.saveStorage(payload);
    ElMessage.success(t('system.saved', { type: form.type.toUpperCase() }));
    await reload();
  } finally { saving.value = false; }
}

async function testConnection() {
  testing.value = true;
  lastTest.value = null;
  try {
    const r = await settingApi.testStorage(form);
    lastTest.value = r;
    if (r.ok) ElMessage.success(t('system.testOk'));
    else ElMessage.warning(t('system.testFail'));
  } catch (e: any) {
    const msg = e?.response?.data?.message || String(e);
    lastTest.value = { ok: false, message: msg, latencyMs: 0, bucket: form.s3.bucket || '' };
    ElMessage.error(`${t('system.testFail')}: ${msg}`);
  } finally {
    testing.value = false;
  }
}

onMounted(reload);
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 10px; }
.page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; }
.page-desc { margin: 4px 0 0; font-size: 13px; }
.header-actions { display: flex; gap: 8px; }
.divider-text { display: inline-flex; align-items: center; gap: 6px; color: var(--color-text-secondary); font-weight: 500; }
.card-title { font-weight: 600; }

.status-card { display: flex; flex-direction: column; }
.status-display {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 12px 8px 4px;
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
.big-icon.local { background: linear-gradient(135deg, #34d399, #10b981); }
.big-icon.s3    { background: linear-gradient(135deg, #fbbf24, #f59e0b); }
.status-name { margin: 0; font-size: 18px; font-weight: 700; }
.status-detail { margin: 8px 0 0; font-size: 13px; color: var(--color-text-secondary); word-break: break-all; }

.btn-label { margin-left: 6px; }

/* 三态 AK/SK 行：撑满 label 之后的 content 区 */
:deep(.key-item .el-form-item__content) {
  flex: 1 1 auto;
  width: 100%;
  max-width: 100%;
}
.key-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; width: 100%; }
.key-row :deep(.el-input) { flex: 1 1 100%; width: 100%; min-width: 0; }
.key-row :deep(.el-input__wrapper) { width: 100%; }
.key-state { font-size: 12px; }

/* 右侧 aside 上 AK/SK 状态行 */
.key-status-row {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  margin-top: 4px;
  border-top: 1px solid var(--color-border-soft);
  font-size: 13px;
}
.key-status-label {
  color: var(--color-text-secondary);
}
.key-status-value {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-weight: 500;
}
.key-status-value.on { color: var(--color-primary); }
.key-status-value.off { color: var(--color-text-secondary); }
.key-status-value.warn { color: var(--color-warning); }
.key-dot { font-size: 14px; }
.key-dot-warn { color: var(--color-warning); }
.muted { color: var(--color-text-secondary); }

.status-foot {
  width: 100%;
  margin-top: 10px;
  padding: 8px 12px;
  background: var(--color-bg-soft);
  border-radius: 4px;
  font-size: 12px;
  color: var(--color-text-secondary);
  line-height: 1.5;
  text-align: left;
}
.status-foot-warn {
  background: #FEF3C7;
  color: #92400E;
  border-left: 3px solid var(--color-warning);
}

.test-result {
  margin-top: 18px;
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 10px 12px;
  border-radius: 6px;
  font-size: 13px;
  width: 100%;
  text-align: left;
  border: 1px solid transparent;
}
.test-result .result-icon {
  font-size: 18px;
  flex-shrink: 0;
  margin-top: 1px;
}
.test-result .result-body { flex: 1; min-width: 0; }
.test-result .result-line { font-weight: 600; }
.test-result .result-detail {
  font-size: 12px;
  font-family: var(--font-mono);
  word-break: break-all;
  color: var(--color-text-secondary);
  margin-top: 2px;
}
.test-result.ok {
  background: #ecfdf5;
  border-color: #6ee7b7;
  color: #047857;
}
.test-result.fail {
  background: #fef2f2;
  border-color: #fca5a5;
  color: #b91c1c;
}
</style>