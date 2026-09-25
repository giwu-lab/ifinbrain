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
              <el-form-item :label="t('system.s3AccessKeyLabel')">
                <el-input v-model="form.s3.accessKey" />
              </el-form-item>
              <el-form-item :label="t('system.s3SecretKeyLabel')">
                <el-input v-model="form.s3.secretKey" type="password" show-password />
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
import { computed, reactive, ref, onMounted } from 'vue';
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

/** S3 模式：endpoint / bucket 必填才能测连通。密钥字段支持「********」掩码（视为保留旧值） */
const canTest = computed(() => {
  if (form.type !== 's3') return false;
  return !!(form.s3.endpoint && form.s3.bucket
    && (form.s3.accessKey || hasSavedAccessKey.value)
    && (form.s3.secretKey || hasSavedSecretKey.value));
});

/** 跟踪后端是否存有 AK / SK（已存时可让用户留空也能测） */
const hasSavedAccessKey = ref(false);
const hasSavedSecretKey = ref(false);

async function reload() {
  loading.value = true;
  lastTest.value = null;
  try {
    const r = await settingApi.storage();
    Object.assign(form, r);
    hasSavedAccessKey.value = !!r.s3.accessKey && r.s3.accessKey.startsWith('********');
    hasSavedSecretKey.value = !!r.s3.secretKey && r.s3.secretKey.startsWith('********');
  } finally { loading.value = false; }
}

async function save() {
  saving.value = true;
  try {
    await settingApi.saveStorage(form);
    ElMessage.success(t('system.saved', { type: form.type.toUpperCase() }));
    reload();
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
.page-header { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 20px; }
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
.big-icon.local { background: linear-gradient(135deg, #34d399, #10b981); }
.big-icon.s3    { background: linear-gradient(135deg, #fbbf24, #f59e0b); }
.status-name { margin: 0; font-size: 18px; font-weight: 700; }
.status-detail { margin: 8px 0 0; font-size: 13px; color: var(--color-text-secondary); word-break: break-all; }

.btn-label { margin-left: 6px; }

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
  font-family: ui-monospace, 'JetBrains Mono', monospace;
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