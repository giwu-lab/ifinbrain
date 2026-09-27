<template>
  <div class="page-container fade-in-up">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('dict.title') }}</h1>
        <p class="page-desc muted">{{ t('dict.desc') }}</p>
      </div>
      <!-- page-header 只保留标题；字典 / 国际化 tab 各自在 pane 内部署操作按钮 -->
    </div>

    <el-tabs v-model="activeTab" class="dict-tabs">
      <el-tab-pane :label="t('dict.tabDicts')" name="dict">
        <div class="dict-panel">
          <div class="dict-pane">
            <!-- 左侧：字典类型树形 -->
            <div class="dict-types">
          <div class="pane-header">
            <span class="pane-title">{{ t('dict.typeTitle') }}</span>
            <div class="pane-tools">
              <el-tooltip :content="t('dict.expandAll')" placement="top">
                <el-button :icon="Plus" size="small" link @click="onCreateType" v-if="auth.hasPerm('dict:update')" />
              </el-tooltip>
              <el-tooltip :content="t('dict.expandAll')" placement="top">
                <el-button :icon="ArrowDown" size="small" link @click="onExpandAll" />
              </el-tooltip>
              <el-tooltip :content="t('dict.collapseAll')" placement="top">
                <el-button :icon="ArrowUp" size="small" link @click="onCollapseAll" />
              </el-tooltip>
            </div>
          </div>
          <el-input
            v-model="filterText"
            :placeholder="t('dict.searchTypePlaceholder')"
            clearable
            size="small"
            class="type-filter"
            :prefix-icon="Search"
          />
          <el-tree
            ref="treeRef"
            :data="filteredTypes"
            :props="{ label: 'name', children: 'children' }"
            node-key="id"
            highlight-current
            default-expand-all
            :expand-on-click-node="false"
            :filter-node-method="filterNode"
            class="type-tree"
            @node-click="onNodeClick"
          >
            <template #default="{ node, data }">
              <span class="tree-node-row" :class="{ leaf: !data.children?.length }">
                <el-icon class="tree-icon" v-if="data.children?.length"><Folder /></el-icon>
                <el-icon class="tree-icon leaf-icon" v-else><Document /></el-icon>
                <span class="tree-label">{{ data.name }}</span>
                <el-tag size="small" effect="plain" class="tree-code">{{ data.code }}</el-tag>
                <span class="tree-count">{{ data.itemCount ?? 0 }}</span>
                <span class="tree-actions" v-if="auth.hasPerm('dict:update') || auth.hasPerm('dict:delete')">
                  <!-- 编辑：图标直显 -->
                  <el-tooltip v-if="auth.hasPerm('dict:update')" :content="t('common.edit')" placement="top">
                    <el-button size="small" link :icon="Edit" @click.stop="onEditType(data)" />
                  </el-tooltip>
                  <!-- 删除：保留在 ··· 下拉（仅对有子条目的 type 显示 —— 空 type 不让删） -->
                  <el-dropdown v-if="auth.hasPerm('dict:delete') && data.children?.length" trigger="click" @command.stop="(cmd: string) => onTypeCmd(cmd, data)">
                    <el-button :icon="MoreFilled" size="small" link />
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item command="delete" divided>
                          <span class="danger-text">
                            <el-icon><Delete /></el-icon>{{ t('common.delete') }}
                          </span>
                        </el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </span>
              </span>
            </template>
          </el-tree>
        </div>

        <!-- 右侧：字典条目 -->
        <div class="dict-items">
          <div class="pane-header items-header">
            <span class="pane-title">
              {{ t('dict.itemTitle') }}
              <span class="muted" v-if="selectedType">— {{ selectedType.name }}</span>
            </span>
            <div class="pane-tools">
              <el-button :icon="Refresh" size="small" :loading="store.loading" @click="onRefreshCache">
                {{ t('dict.refreshCache') }}
              </el-button>
              <span class="cache-hint muted" v-if="store.loadedAt">
                {{ t('dict.cacheLoadedAt', { time: formatTime(store.loadedAt) }) }}
              </span>
              <el-button :icon="Download" size="small" @click="onExport" :loading="exporting">
                {{ t('dict.exportBtn') }}
              </el-button>
              <el-button type="primary" :icon="UploadFilled" size="small" @click="importDialog = true">
                {{ t('dict.importBtn') }}
              </el-button>
              <el-button
                v-if="auth.hasPerm('dict:update') && selectedType"
                :icon="Plus"
                size="small"
                @click="onCreateItem"
              >{{ t('dict.newItem') }}</el-button>
            </div>
          </div>
          <el-table
            v-if="selectedType"
            :data="items"
            v-loading="itemsLoading"
            border
            stripe
            size="small"
            max-height="640"
            >
            <el-table-column :label="t('dict.colCode')" prop="code" min-width="120">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ row.code }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('dict.colLabel')" prop="label" min-width="160" />
            <el-table-column :label="t('dict.colValue')" prop="value" min-width="120">
              <template #default="{ row }">
                <span class="text-secondary">{{ row.value || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('dict.colParent')" prop="parentId" width="80" align="right">
              <template #default="{ row }">{{ row.parentId || '—' }}</template>
            </el-table-column>
            <el-table-column :label="t('dict.colSort')" prop="sort" width="80" align="right" />
            <el-table-column :label="t('dict.colStatus')" width="80">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small" effect="light">
                  {{ row.status === 1 ? t('common.enabled') : t('common.disabled') }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.actions')" width="120" align="center" fixed="right">
              <template #default="{ row }">
                <!-- 编辑：图标直显 -->
                <el-tooltip v-if="auth.hasPerm('dict:update')" :content="t('common.edit')" placement="top">
                  <el-button size="small" link :icon="Edit" @click="onEditItem(row)" />
                </el-tooltip>
                <!-- 删除：保留在 ··· 下拉 -->
                <el-dropdown v-if="auth.hasPerm('dict:delete')" trigger="click" @command="(cmd: string) => onItemCmd(cmd, row)">
                  <el-button :icon="MoreFilled" size="small" link />
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="delete" divided>
                        <span class="danger-text">
                          <el-icon><Delete /></el-icon>{{ t('common.delete') }}
                        </span>
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-else :description="t('dict.selectTypeFirst')" />
        </div>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane :label="t('dict.tabI18n')" name="i18n">
        <I18nPanel />
      </el-tab-pane>
    </el-tabs>

    <!-- 类型编辑弹窗 -->
    <el-dialog
      v-model="typeDialog"
      :title="typeForm.id ? t('dict.typeEditTitle') : t('dict.typeNewTitle')"
      width="520px"
      append-to-body
      destroy-on-close
      align-center
    >
      <el-form :model="typeForm" label-width="100px">
        <el-form-item :label="t('dict.colCode')" required>
          <el-input v-model="typeForm.code" :disabled="!!typeForm.id" :placeholder="t('dict.codePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('dict.colName')" required>
          <el-input v-model="typeForm.name" />
        </el-form-item>
        <el-form-item :label="t('dict.colRemark')">
          <el-input v-model="typeForm.remark" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item :label="t('dict.colStatus')">
          <el-switch v-model="typeForm.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="typeDialog = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="typeSaving" @click="saveType">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- 条目编辑弹窗 -->
    <el-dialog
      v-model="itemDialog"
      :title="itemForm.id ? t('dict.itemEditTitle') : t('dict.itemNewTitle')"
      width="520px"
      append-to-body
      destroy-on-close
      align-center
    >
      <el-form :model="itemForm" label-width="100px">
        <el-form-item :label="t('dict.colCode')" required>
          <el-input v-model="itemForm.code" :disabled="!!itemForm.id" :placeholder="t('dict.codePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('dict.colLabel')" required>
          <el-input v-model="itemForm.label" />
        </el-form-item>
        <el-form-item :label="t('dict.colValue')">
          <el-input v-model="itemForm.value" />
        </el-form-item>
        <el-form-item :label="t('dict.colParent')">
          <el-input-number v-model="itemForm.parentId" :min="0" :step="1" />
        </el-form-item>
        <el-form-item :label="t('dict.colSort')">
          <el-input-number v-model="itemForm.sort" :min="0" :step="1" />
        </el-form-item>
        <el-form-item :label="t('dict.colStatus')">
          <el-switch v-model="itemForm.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialog = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="itemSaving" @click="saveItem">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- 批量导入弹窗 -->
    <el-dialog
      v-model="importDialog"
      :title="t('dict.importTitle')"
      width="560px"
      append-to-body
      destroy-on-close
      align-center
    >
      <p class="import-hint">{{ t('dict.importFormatHint') }}</p>
      <el-upload
        ref="importUploaderRef"
        drag
        :auto-upload="false"
        :limit="1"
        :on-change="onImportFileChange"
        :on-exceed="() => ElMessage.warning(t('common.required'))"
        accept=".json,.csv"
      >
        <el-icon class="upload-icon"><UploadFilled /></el-icon>
        <div class="el-upload__text">{{ t('dict.importChooseFile') }}</div>
        <template #tip>
          <div class="el-upload__tip">{{ t('dict.importTip') }}</div>
        </template>
      </el-upload>
      <div v-if="importFile" class="import-preview">
        <el-checkbox v-model="importDryRun">{{ t('dict.importDryRun') }}</el-checkbox>
        <p>{{ t('dict.importPreview', { count: importPreviewCount }) }}</p>
      </div>
      <template #footer>
        <el-button @click="closeImportDialog">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="importing" :disabled="!importFile" @click="onImportConfirm">
          {{ importDryRun ? t('dict.importDryRun') : t('dict.importConfirm') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  Plus, Refresh, Search, Folder, Document,
  ArrowDown, ArrowUp, UploadFilled, Download,
  Edit, Delete, MoreFilled,
  type UploadFile, type UploadInstance,
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { dictApi, type DictItem, type DictType } from '@/api/dict';
import { useDictStore } from '@/stores/dict';
import I18nPanel from './I18nPanel.vue';

const { t } = useI18n();
const auth = useAuthStore();
const store = useDictStore();

const activeTab = ref<'dict' | 'i18n'>('dict');

const types = ref<DictType[]>([]);
const typesLoading = ref(false);
const selectedType = ref<DictType | null>(null);
const items = ref<DictItem[]>([]);
const itemsLoading = ref(false);

const treeRef = ref();
const filterText = ref('');

const typeDialog = ref(false);
const typeSaving = ref(false);
const typeForm = reactive<{ id?: number; code: string; name: string; remark: string; status: 0 | 1 }>({
  code: '', name: '', remark: '', status: 1,
});

const itemDialog = ref(false);
const itemSaving = ref(false);
const itemForm = reactive<{
  id?: number; code: string; label: string; value: string;
  parentId: number; sort: number; status: 0 | 1;
}>({ code: '', label: '', value: '', parentId: 0, sort: 0, status: 1 });

const importDialog = ref(false);
const importUploaderRef = ref<UploadInstance>();
const importFile = ref<File | null>(null);
const importDryRun = ref(true);
const importPreviewCount = ref(0);
const importing = ref(false);
const exporting = ref(false);

/** 把顶层 types 包成 el-tree 期望的 {children:[]} 节点 */
const treeData = computed(() => types.value.map((t) => ({
  ...t,
  children: [] as undefined[], // 顶层无 children
})));

const filteredTypes = computed(() => {
  const txt = filterText.value.trim().toLowerCase();
  if (!txt) return treeData.value;
  return treeData.value.filter(
    (t) => t.name.toLowerCase().includes(txt) || t.code.toLowerCase().includes(txt)
  );
});

function formatTime(ts: number) {
  const d = new Date(ts);
  return `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}:${d.getSeconds().toString().padStart(2, '0')}`;
}

async function loadTypes() {
  typesLoading.value = true;
  try {
    types.value = await dictApi.listTypes();
    if (!selectedType.value && types.value.length) {
      await onNodeClick(types.value[0]);
    }
  } finally {
    typesLoading.value = false;
  }
}

async function onNodeClick(node: DictType) {
  selectedType.value = node;
  await loadItems(node.code);
}

async function loadItems(code: string) {
  itemsLoading.value = true;
  try {
    items.value = await dictApi.listItems(code);
  } finally {
    itemsLoading.value = false;
  }
}

function filterNode(value: string, data: DictType) {
  if (!value) return true;
  return data.name.toLowerCase().includes(value) || data.code.toLowerCase().includes(value);
}

watch(filterText, (val) => {
  treeRef.value?.filter(val);
});

function onExpandAll() {
  const nodes = treeRef.value?.store?.nodesMap || {};
  Object.values(nodes).forEach((n: any) => { n.expanded = true; });
}
function onCollapseAll() {
  const nodes = treeRef.value?.store?.nodesMap || {};
  Object.values(nodes).forEach((n: any) => { n.expanded = false; });
}

function resetTypeForm() {
  typeForm.id = undefined;
  typeForm.code = ''; typeForm.name = ''; typeForm.remark = ''; typeForm.status = 1;
}

function onCreateType() {
  resetTypeForm();
  typeDialog.value = true;
}

function onEditType(row: DictType) {
  resetTypeForm();
  Object.assign(typeForm, {
    id: row.id,
    code: row.code,
    name: row.name,
    remark: row.remark ?? '',
    status: (row.status ?? 1) as 0 | 1,
  });
  typeDialog.value = true;
}

async function saveType() {
  if (!typeForm.code.trim() || !typeForm.name.trim()) {
    ElMessage.warning(t('dict.codeNameRequired'));
    return;
  }
  typeSaving.value = true;
  try {
    if (typeForm.id) {
      await dictApi.updateType(typeForm.id, {
        name: typeForm.name, remark: typeForm.remark, status: typeForm.status,
      });
    } else {
      await dictApi.createType({
        code: typeForm.code, name: typeForm.name,
        remark: typeForm.remark, status: typeForm.status,
      });
    }
    ElMessage.success(t('common.updated'));
    typeDialog.value = false;
    await store.refresh();
    await loadTypes();
  } catch (e) {
    ElMessage.error((e instanceof Error ? e.message : String(e)));
  } finally {
    typeSaving.value = false;
  }
}

async function onDeleteType(row: DictType) {
  try {
    await dictApi.deleteType(row.id);
    ElMessage.success(t('common.deleted'));
    if (selectedType.value?.id === row.id) {
      selectedType.value = null;
      items.value = [];
    }
    await store.refresh();
    await loadTypes();
  } catch (e) {
    ElMessage.error((e instanceof Error ? e.message : String(e)));
  }
}

/** 字典类型树 ··· 下拉菜单统一入口 */
async function onTypeCmd(cmd: string, row: DictType) {
  if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(
        t('dict.confirmDeleteType', { name: row.name }),
        t('common.confirmDelete'),
        { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') }
      );
      await onDeleteType(row);
    } catch { /* 用户取消 */ }
  }
}

function resetItemForm() {
  itemForm.id = undefined;
  itemForm.code = ''; itemForm.label = ''; itemForm.value = '';
  itemForm.parentId = 0; itemForm.sort = 0; itemForm.status = 1;
}

function onCreateItem() {
  if (!selectedType.value) return;
  resetItemForm();
  itemDialog.value = true;
}

function onEditItem(row: DictItem) {
  resetItemForm();
  Object.assign(itemForm, {
    id: row.id, code: row.code, label: row.label, value: row.value ?? '',
    parentId: row.parentId ?? 0, sort: row.sort ?? 0, status: (row.status ?? 1) as 0 | 1,
  });
  itemDialog.value = true;
}

async function saveItem() {
  if (!selectedType.value) return;
  if (!itemForm.code.trim() || !itemForm.label.trim()) {
    ElMessage.warning(t('dict.codeLabelRequired'));
    return;
  }
  itemSaving.value = true;
  try {
    if (itemForm.id) {
      await dictApi.updateItem(itemForm.id, {
        label: itemForm.label, value: itemForm.value,
        parentId: itemForm.parentId, sort: itemForm.sort, status: itemForm.status,
      });
    } else {
      await dictApi.createItem(selectedType.value.id, {
        code: itemForm.code, label: itemForm.label, value: itemForm.value,
        parentId: itemForm.parentId, sort: itemForm.sort, status: itemForm.status,
      });
    }
    ElMessage.success(t('common.updated'));
    itemDialog.value = false;
    await store.refresh();
    await loadItems(selectedType.value.code);
    await loadTypes();
  } catch (e) {
    ElMessage.error((e instanceof Error ? e.message : String(e)));
  } finally {
    itemSaving.value = false;
  }
}

async function onDeleteItem(row: DictItem) {
  try {
    await dictApi.deleteItem(row.id);
    ElMessage.success(t('common.deleted'));
    if (selectedType.value) await loadItems(selectedType.value.code);
    await store.refresh();
    await loadTypes();
  } catch (e) {
    ElMessage.error((e instanceof Error ? e.message : String(e)));
  }
}

/** ··· 下拉菜单统一入口 */
async function onItemCmd(cmd: string, row: DictItem) {
  if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(
        t('dict.confirmDeleteItem', { code: row.code }),
        t('common.confirmDelete'),
        { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') }
      );
      await onDeleteItem(row);
    } catch { /* 用户取消 */ }
  }
}

async function onRefreshCache() {
  await store.refresh();
  ElMessage.success(t('dict.cacheRefreshed'));
}

// ============= 导出 =============
async function onExport() {
  exporting.value = true;
  try {
    const token = localStorage.getItem('accessToken') || '';
    const res = await fetch(dictApi.exportUrl(), {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const blob = await res.blob();
    const disp = res.headers.get('Content-Disposition') || '';
    const m = /filename\*?=(?:UTF-8'')?["']?([^"';]+)/i.exec(disp);
    const filename = m ? decodeURIComponent(m[1]) : 'dicts.json';
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = filename;
    document.body.appendChild(a); a.click(); a.remove();
    URL.revokeObjectURL(url);
    ElMessage.success(t('dict.exportOk', { count: types.value.length }));
  } catch (e) {
    ElMessage.error(t('dict.exportFail', { msg: (e instanceof Error ? e.message : String(e)) }));
  } finally {
    exporting.value = false;
  }
}

// ============= 导入 =============
function onImportFileChange(file: UploadFile) {
  const raw = file.raw as File | undefined;
  if (!raw) return;
  importFile.value = raw;
  // 估算行数做预览
  if (raw.name.toLowerCase().endsWith('.json')) {
    raw.text().then((txt) => {
      try {
        const arr = JSON.parse(txt);
        let n = 0;
        if (Array.isArray(arr)) {
          for (const t of arr) {
            if (Array.isArray(t.items)) n += t.items.length;
            n += 1; // 类型本身
          }
        }
        importPreviewCount.value = n;
      } catch {
        importPreviewCount.value = 0;
      }
    });
  } else {
    // CSV：简单按行数 - 1
    raw.text().then((txt) => {
      const lines = txt.split(/\r?\n/).filter(Boolean);
      importPreviewCount.value = Math.max(lines.length - 1, 0);
    });
  }
}

async function onImportConfirm() {
  if (!importFile.value) {
    ElMessage.warning(t('dict.importInvalidFile'));
    return;
  }
  importing.value = true;
  try {
    const r = await dictApi.importFile(importFile.value, importDryRun.value);
    if (importDryRun.value) {
      ElMessageBox.alert(
        t('dict.importDryRunOk', { ok: r.ok, skip: r.skipped, err: r.error })
          + (r.messages?.length ? '\n\n' + r.messages.join('\n') : ''),
        t('dict.importTitle'),
        { type: r.error > 0 ? 'warning' : 'info' },
      );
    } else {
      ElMessageBox.alert(
        t('dict.importOk', { ok: r.ok, update: r.updated, skip: r.skipped, err: r.error })
          + (r.messages?.length ? '\n\n' + r.messages.join('\n') : ''),
        t('dict.importTitle'),
        { type: r.error > 0 ? 'warning' : 'success' },
      );
      await store.refresh();
      await loadTypes();
      if (selectedType.value) await loadItems(selectedType.value.code);
      closeImportDialog();
    }
  } catch (e) {
    ElMessage.error(t('dict.importFail', { msg: (e instanceof Error ? e.message : String(e)) }));
  } finally {
    importing.value = false;
  }
}

function closeImportDialog() {
  importDialog.value = false;
  importFile.value = null;
  importPreviewCount.value = 0;
  importUploaderRef.value?.clearFiles();
}

onMounted(async () => {
  await store.load();
  await loadTypes();
});

watch(() => types.value.length, (n) => {
  if (!selectedType.value && n > 0) onNodeClick(types.value[0]);
});
</script>

<style scoped>
.page-header {
  margin-bottom: 20px;
}
.page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; }
.page-desc { margin: 4px 0 0; font-size: 13px; }
.cache-hint { font-size: 12px; align-self: center; }

.dict-panel {
  height: calc(100vh - 220px);
  min-height: 540px;
}
.dict-pane {
  display: grid;
  grid-template-columns: 360px 1fr;
  gap: 12px;
  height: 100%;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 6px;
  overflow: hidden;
}
.dict-types { border-right: 1px solid var(--line); padding: 16px; display: flex; flex-direction: column; gap: 10px; min-width: 0; background: #fff; }
.dict-items { padding: 16px; min-width: 0; background: #fff; }

.pane-header { display: flex; align-items: center; justify-content: space-between; padding: 10px 16px; }
.pane-title { font-weight: 600; font-size: 14px; color: var(--color-text); }
.pane-title .muted { font-weight: 400; margin-left: 8px; }
.pane-tools { display: inline-flex; gap: 6px; align-items: center; }
.pane-tools :deep(.el-button) { margin-left: 0; }
.items-header { border-bottom: 1px solid var(--line); margin-bottom: 0; padding-bottom: 10px; }

.type-filter { width: 100%; }

.type-tree {
  flex: 1;
  overflow-y: auto;
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 6px 0;
}

.tree-node-row {
  display: inline-flex; align-items: center; gap: 6px;
  width: 100%;
  font-size: 13px;
  padding-right: 8px;
}
.tree-icon { color: var(--color-primary); flex-shrink: 0; }
.tree-icon.leaf-icon { color: var(--color-text-secondary); }
.tree-label { font-weight: 500; flex-shrink: 0; max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tree-code { font-family: monospace; font-size: 11px; }
.tree-count { color: var(--color-text-secondary); font-size: 12px; margin-left: auto; }
.tree-actions { display: inline-flex; gap: 0; margin-left: 8px; opacity: 0.6; transition: opacity .15s; }
.tree-node-row:hover .tree-actions { opacity: 1; }
.tree-actions .el-button { padding: 0 4px; font-size: 12px; }

.import-hint { font-size: 13px; color: var(--color-text-secondary); margin: 0 0 12px; }
.import-preview { margin-top: 12px; font-size: 13px; }
.import-preview p { margin: 8px 0 0; color: var(--color-text-secondary); }
.upload-icon { font-size: 36px; color: var(--color-primary); }

@media (max-width: 1100px) {
  .dict-panel { height: auto; }
  .dict-pane { grid-template-columns: 1fr; height: auto; }
  .dict-types { border-right: none; border-bottom: 1px solid var(--line); }
}
</style>