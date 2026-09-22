<template>
  <div class="i18n-panel">
    <div class="i18n-pane">
      <!-- 左侧：namespace 列表 -->
      <div class="i18n-namespaces">
        <div class="pane-header">
          <span class="pane-title">{{ t('dict.i18nNsTitle') }}</span>
          <div class="pane-tools">
            <el-tooltip :content="t('dict.i18nRefresh')" placement="top">
              <el-button :icon="Refresh" size="small" link @click="refreshAll" />
            </el-tooltip>
          </div>
        </div>
        <el-input
          v-model="nsFilter"
          :placeholder="t('dict.i18nNsSearch')"
          clearable
          size="small"
          class="ns-filter"
          :prefix-icon="Search"
        />
        <div class="ns-list">
          <div
            v-for="ns in filteredNamespaces"
            :key="ns"
            class="ns-item"
            :class="{ active: ns === selectedNs }"
            @click="onSelectNs(ns)"
          >
            <span class="ns-name">{{ ns }}</span>
            <el-tag size="small" round class="ns-count">{{ counts[ns] || 0 }}</el-tag>
          </div>
          <el-empty v-if="!filteredNamespaces.length" :description="t('dict.i18nNoNs')" />
        </div>
      </div>

      <!-- 右侧：当前 namespace 的条目 -->
      <div class="i18n-items">
        <div class="pane-header items-header">
          <span class="pane-title">
            {{ t('dict.i18nItemsTitle') }}
            <el-tag size="small" type="info" round class="ns-tag">{{ selectedNs || '—' }}</el-tag>
          </span>
          <div class="pane-tools">
            <el-tooltip :content="t('dict.i18nSyncTip')" placement="top">
              <el-button :icon="MagicStick" size="small" @click="onSyncDefaults" :loading="syncLoading" v-if="auth.hasPerm('i18n:update')">
                {{ t('dict.i18nSyncDefaults') }}
              </el-button>
            </el-tooltip>
            <el-button :icon="Plus" size="small" @click="onAdd" :disabled="!selectedNs" v-if="auth.hasPerm('i18n:update')">
              {{ t('dict.i18nAdd') }}
            </el-button>
            <el-button :icon="RefreshLeft" size="small" @click="onResetNs" :disabled="!selectedNs" v-if="auth.hasPerm('i18n:delete')">
              {{ t('dict.i18nReset') }}
            </el-button>
            <el-button :icon="Download" size="small" @click="onExport">
              {{ t('dict.i18nExport') }}
            </el-button>
            <el-button :icon="UploadFilled" size="small" type="primary" @click="onImport" v-if="auth.hasPerm('i18n:update')">
              {{ t('dict.i18nImport') }}
            </el-button>
          </div>
        </div>

        <el-table
          :data="items"
          v-loading="loading"
          height="100%"
          size="default"
          stripe
          class="i18n-table"
        >
          <el-table-column :label="t('dict.i18nColCode')" prop="code" min-width="200" fixed>
            <template #default="{ row }">
              <code class="code-text">{{ row.code }}</code>
            </template>
          </el-table-column>
          <el-table-column :label="t('dict.i18nColZh')" prop="valueZh" min-width="240">
            <template #default="{ row }">
              <span class="text-cell">{{ row.valueZh || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('dict.i18nColEn')" prop="valueEn" min-width="240">
            <template #default="{ row }">
              <span class="text-cell">{{ row.valueEn || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('dict.i18nColUpdatedAt')" prop="updatedAt" min-width="160">
            <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column :label="t('common.actions')" width="160" fixed="right" v-if="auth.hasPerm('i18n:update') || auth.hasPerm('i18n:delete')">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="openEdit(row)" v-if="auth.hasPerm('i18n:update')">{{ t('common.edit') }}</el-button>
              <el-button size="small" link type="danger" @click="onDelete(row)" v-if="auth.hasPerm('i18n:delete')">{{ t('common.delete') }}</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty :description="selectedNs ? t('dict.i18nNoItems') : t('dict.i18nSelectNsFirst')" />
          </template>
        </el-table>
      </div>
    </div>

    <!-- 编辑弹窗 -->
    <el-dialog
      v-model="editDialog"
      :title="t('dict.i18nEditTitle')"
      width="640px"
      append-to-body
      destroy-on-close
    >
      <el-form :model="editForm" label-width="100px" v-if="editDialog">
        <el-form-item :label="t('dict.i18nNamespace')">
          <el-input v-model="editForm.namespace" disabled />
        </el-form-item>
        <el-form-item :label="t('dict.i18nColCode')">
          <el-input v-model="editForm.code" disabled />
        </el-form-item>
        <el-form-item :label="t('dict.i18nColZh')">
          <el-input
            v-model="editForm.valueZh"
            type="textarea"
            :rows="4"
            :placeholder="t('dict.i18nColZh')"
            resize="vertical"
          />
        </el-form-item>
        <el-form-item :label="t('dict.i18nColEn')">
          <el-input
            v-model="editForm.valueEn"
            type="textarea"
            :rows="4"
            :placeholder="t('dict.i18nColEn')"
            resize="vertical"
          />
        </el-form-item>
        <el-form-item v-if="editForm.updatedAt">
          <span class="muted">{{ t('dict.i18nColUpdatedAt') }}: {{ formatTime(editForm.updatedAt) }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="editSaving" @click="submitEdit">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- 新增弹窗 -->
    <el-dialog v-model="addDialog" :title="t('dict.i18nAddTitle')" width="560px" append-to-body>
      <el-form :model="addForm" label-width="100px">
        <el-form-item :label="t('dict.i18nNamespace')" required>
          <el-select v-model="addForm.namespace" filterable allow-create :placeholder="t('dict.i18nNamespacePh')" style="width: 100%">
            <el-option v-for="ns in namespaces" :key="ns" :label="ns" :value="ns" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('dict.i18nColCode')" required>
          <el-input v-model="addForm.code" :placeholder="t('dict.i18nCodePh')" />
        </el-form-item>
        <el-form-item :label="t('dict.i18nColZh')">
          <el-input v-model="addForm.valueZh" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item :label="t('dict.i18nColEn')">
          <el-input v-model="addForm.valueEn" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addDialog = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="addSaving" @click="saveAdd">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- 导入弹窗 -->
    <el-dialog v-model="importDialog" :title="t('dict.i18nImportTitle')" width="480px" append-to-body>
      <el-form label-width="100px">
        <el-form-item :label="t('dict.i18nImportMode')">
          <el-radio-group v-model="importMode">
            <el-radio-button value="merge">{{ t('dict.i18nImportModeMerge') }}</el-radio-button>
            <el-radio-button value="replace">{{ t('dict.i18nImportModeReplace') }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('dict.i18nImportFile')">
          <el-upload
            ref="uploadRef"
            :auto-upload="false"
            :limit="1"
            accept=".json"
            :on-change="onFileChange"
            :on-exceed="onExceed"
          >
            <el-button :icon="UploadFilled">{{ t('dict.i18nImportChoose') }}</el-button>
            <template #tip>
              <div class="el-upload__tip">{{ t('dict.i18nImportTip') }}</div>
            </template>
          </el-upload>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importDialog = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="importing" :disabled="!fileToImport" @click="submitImport">{{ t('dict.i18nImportSubmit') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  Plus as PlusEl, Refresh, RefreshLeft, Search, Download, UploadFilled, MagicStick,
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { i18nApi, type I18nMessage } from '@/api/i18n';
import { useI18nStore } from '@/stores/i18n';

const { t } = useI18n();
const auth = useAuthStore();
const i18nStore = useI18nStore();

const Plus = PlusEl;

const namespaces = computed(() => i18nStore.namespaces);
const counts = computed(() => i18nStore.counts);

const nsFilter = ref('');
const selectedNs = ref<string>('');
const items = ref<I18nMessage[]>([]);
const loading = ref(false);

const filteredNamespaces = computed(() => {
  if (!nsFilter.value) return namespaces.value;
  const f = nsFilter.value.toLowerCase();
  return namespaces.value.filter((n) => n.toLowerCase().includes(f));
});

async function refreshAll() {
  await i18nStore.refreshMeta();
  if (selectedNs.value) await loadItems(selectedNs.value);
}

async function onSelectNs(ns: string) {
  selectedNs.value = ns;
  await loadItems(ns);
}

async function loadItems(ns: string) {
  loading.value = true;
  try {
    const list = await i18nApi.listByNamespace(ns);
    items.value = list.map((m) => {
      const v = parseValueJson(m.valueJson);
      return { ...m, valueZh: v.zhCN || '', valueEn: v.enUS || '' };
    });
  } catch (e: any) {
    ElMessage.error(e?.message || String(e));
  } finally {
    loading.value = false;
  }
}

function parseValueJson(s: string): { zhCN: string; enUS: string } {
  try {
    const o = JSON.parse(s);
    return { zhCN: o['zh-CN'] || '', enUS: o['en-US'] || '' };
  } catch {
    return { zhCN: '', enUS: '' };
  }
}

function buildValueJson(zh: string, en: string): string {
  return JSON.stringify({ 'zh-CN': zh, 'en-US': en });
}

// ============== 编辑弹窗 ==============
const editDialog = ref(false);
const editSaving = ref(false);
const editForm = reactive<{ id: number | null; namespace: string; code: string; valueZh: string; valueEn: string; updatedAt: string }>({
  id: null,
  namespace: '',
  code: '',
  valueZh: '',
  valueEn: '',
  updatedAt: '',
});

function openEdit(row: I18nMessage & { valueZh: string; valueEn: string }) {
  editForm.id = row.id;
  editForm.namespace = row.namespace;
  editForm.code = row.code;
  editForm.valueZh = row.valueZh;
  editForm.valueEn = row.valueEn;
  editForm.updatedAt = row.updatedAt;
  editDialog.value = true;
}

async function submitEdit() {
  if (!editForm.namespace || !editForm.code) return;
  editSaving.value = true;
  try {
    await i18nApi.upsert(editForm.namespace, editForm.code, buildValueJson(editForm.valueZh, editForm.valueEn));
    ElMessage.success(t('common.saveSuccess'));
    editDialog.value = false;
    await Promise.all([i18nStore.refresh(), i18nStore.refreshMeta()]);
    await loadItems(editForm.namespace);
  } catch (e: any) {
    ElMessage.error(e?.message || String(e));
  } finally {
    editSaving.value = false;
  }
}

async function onDelete(row: I18nMessage) {
  try {
    await ElMessageBox.confirm(
      t('dict.i18nDeleteConfirm', { ns: row.namespace, code: row.code }),
      t('common.confirmTitle'),
      { type: 'warning' },
    );
    await i18nApi.delete(row.namespace, row.code);
    ElMessage.success(t('common.deleteSuccess'));
    await Promise.all([i18nStore.refresh(), i18nStore.refreshMeta()]);
    await loadItems(row.namespace);
  } catch (e: any) {
    if (e === 'cancel' || e?.message === 'cancel') return;
    ElMessage.error(e?.message || String(e));
  }
}

// ============== 新增 ==============
const addDialog = ref(false);
const addSaving = ref(false);
const addForm = reactive({ namespace: '', code: '', valueZh: '', valueEn: '' });

function onAdd() {
  addForm.namespace = selectedNs.value || '';
  addForm.code = '';
  addForm.valueZh = '';
  addForm.valueEn = '';
  addDialog.value = true;
}

async function saveAdd() {
  if (!addForm.namespace || !addForm.code) {
    ElMessage.warning(t('dict.i18nCodeRequired'));
    return;
  }
  addSaving.value = true;
  try {
    await i18nApi.upsert(addForm.namespace, addForm.code, buildValueJson(addForm.valueZh, addForm.valueEn));
    ElMessage.success(t('common.saveSuccess'));
    addDialog.value = false;
    selectedNs.value = addForm.namespace;
    await Promise.all([i18nStore.refresh(), i18nStore.refreshMeta()]);
    await loadItems(addForm.namespace);
  } catch (e: any) {
    ElMessage.error(e?.message || String(e));
  } finally {
    addSaving.value = false;
  }
}

// ============== 同步 TS 默认值 ==============
const syncLoading = ref(false);

async function onSyncDefaults() {
  try {
    await ElMessageBox.confirm(
      t('dict.i18nSyncConfirm'),
      t('common.confirmTitle'),
      { type: 'warning' },
    );
  } catch (e: any) {
    if (e === 'cancel' || e?.message === 'cancel') return;
    ElMessage.error(e?.message || String(e));
    return;
  }
  syncLoading.value = true;
  try {
    const r = await i18nApi.syncDefaults();
    ElMessage.success(t('dict.i18nSyncDone', { inserted: r.inserted, updated: r.updated, skipped: r.skipped }));
    await Promise.all([i18nStore.refresh(), i18nStore.refreshMeta()]);
    if (selectedNs.value) await loadItems(selectedNs.value);
  } catch (e: any) {
    ElMessage.error(e?.message || String(e));
  } finally {
    syncLoading.value = false;
  }
}

// ============== 重置 namespace ==============
async function onResetNs() {
  if (!selectedNs.value) return;
  try {
    await ElMessageBox.confirm(
      t('dict.i18nResetConfirm', { ns: selectedNs.value }),
      t('common.confirmTitle'),
      { type: 'warning' },
    );
    await i18nApi.resetNamespace(selectedNs.value);
    ElMessage.success(t('dict.i18nResetDone'));
    await Promise.all([i18nStore.refresh(), i18nStore.refreshMeta()]);
    await loadItems(selectedNs.value);
  } catch (e: any) {
    if (e === 'cancel' || e?.message === 'cancel') return;
    ElMessage.error(e?.message || String(e));
  }
}

// ============== 导出 / 导入 ==============
async function onExport() {
  const token = localStorage.getItem('accessToken') || '';
  try {
    const res = await fetch(i18nApi.exportUrl(), {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `i18n-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e: any) {
    ElMessage.error(e?.message || String(e));
  }
}

const importDialog = ref(false);
const importing = ref(false);
const importMode = ref<'merge' | 'replace'>('merge');
const fileToImport = ref<File | null>(null);
const uploadRef = ref();

function onImport() {
  fileToImport.value = null;
  importDialog.value = true;
}

function onFileChange(file: any) {
    const raw: File | undefined = file?.raw;
    if (raw) fileToImport.value = raw;
  }

function onExceed() {
  ElMessage.warning(t('dict.i18nImportOneOnly'));
}

async function submitImport() {
  if (!fileToImport.value) return;
  importing.value = true;
  try {
    const r = await i18nApi.importFile(fileToImport.value, importMode.value);
    ElMessage.success(
      t('dict.i18nImportDone', { inserted: r.inserted, updated: r.updated, skipped: r.skipped }),
    );
    importDialog.value = false;
    await Promise.all([i18nStore.refresh(), i18nStore.refreshMeta()]);
    if (selectedNs.value) await loadItems(selectedNs.value);
  } catch (e: any) {
    ElMessage.error(e?.message || String(e));
  } finally {
    importing.value = false;
  }
}

function formatTime(s: string) {
  if (!s) return '';
  const d = new Date(s);
  if (isNaN(d.getTime())) return s;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

onMounted(async () => {
  await i18nStore.refreshMeta();
  if (namespaces.value.length) selectedNs.value = namespaces.value[0];
  if (selectedNs.value) await loadItems(selectedNs.value);
});
</script>

<style scoped>
.i18n-panel {
  height: calc(100vh - 220px);
  min-height: 540px;
}

.i18n-pane {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: 12px;
  height: 100%;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  overflow: hidden;
}

.i18n-namespaces,
.i18n-items {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.i18n-namespaces {
  border-right: 1px solid #ebeef5;
  background: #fafbfc;
}

.ns-filter {
  margin: 0 12px 8px;
}

.ns-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px 8px 12px;
}

.ns-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  margin-bottom: 4px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  color: #303133;
  transition: background 0.15s;
}

.ns-item:hover {
  background: #f0f2f5;
}

.ns-item.active {
  background: #ecf5ff;
  color: #409eff;
  font-weight: 500;
}

.ns-name {
  font-family: ui-monospace, 'JetBrains Mono', monospace;
}

.ns-count {
  margin-left: 8px;
}

.items-header {
  border-bottom: 1px solid #ebeef5;
}

.ns-tag {
  margin-left: 8px;
  font-family: ui-monospace, 'JetBrains Mono', monospace;
}

.i18n-items :deep(.pane-header) {
  padding: 10px 16px;
}

.i18n-items :deep(.el-table) {
  flex: 1;
}

.i18n-table {
  width: 100%;
  height: 100%;
}

.code-text {
  font-family: ui-monospace, 'JetBrains Mono', monospace;
  font-size: 12px;
  color: #606266;
  background: #f5f7fa;
  padding: 2px 6px;
  border-radius: 3px;
}

.pane-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid #ebeef5;
}

.pane-title {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
}

.pane-tools {
  display: flex;
  gap: 6px;
}

.pane-tools :deep(.el-button) {
  margin-left: 0;
}

.text-cell {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}

.muted {
  color: #909399;
  font-size: 12px;
}
</style>