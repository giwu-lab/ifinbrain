<template>
  <div class="page-container fade-in-up">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('system.storageTitle') }}</h1>
        <p class="page-desc muted">{{ t('system.storageTitleDesc') }}</p>
      </div>
      <div class="header-actions">
        <el-button @click="reload" :loading="loading">{{ t('common.reload') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('common.save') }}</el-button>
      </div>
    </div>

    <el-row :gutter="20">
      <el-col :md="24" :lg="14">
        <el-card class="card-table" shadow="never">
          <el-form :model="form" label-width="160px">
            <el-form-item :label="t('system.storageTypeLabel')">
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
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { Folder, FolderOpened, Coin } from '@element-plus/icons-vue';
import { settingApi, type StorageConfig } from '@/api/setting';

const { t } = useI18n();
const loading = ref(false);
const saving = ref(false);
const form = reactive<StorageConfig>({
  type: 'local',
  local: { root: './data/storage' },
  s3: { endpoint: '', region: 'us-east-1', bucket: '', accessKey: '', secretKey: '', pathStyle: true }
});

async function reload() {
  loading.value = true;
  try {
    const r = await settingApi.storage();
    Object.assign(form, r);
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
</style>