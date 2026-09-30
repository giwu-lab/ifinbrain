<template>
  <div class="page-container fade-in-up flow-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('design.flowTitle') }}</h1>
        <p class="page-desc muted">{{ t('design.flowDesc') }}</p>
      </div>
      <div class="header-actions">
        <el-button
          v-if="auth.hasPerm('design:flow:create')"
          type="primary"
          :icon="Plus"
          @click="goCreate"
        >{{ t('design.flowCreate') }}</el-button>
      </div>
    </div>

    <div class="flow-table-wrap">
      <el-table :data="rows" v-loading="loading" border stripe size="small">
        <el-table-column :label="t('design.flowColCode')" prop="code" min-width="160">
          <template #default="{ row }">
            <code class="mono">{{ (row as FlowDefineItem).code }}</code>
          </template>
        </el-table-column>
        <el-table-column :label="t('design.flowColName')" prop="name" min-width="160" />
        <el-table-column :label="t('design.flowColDescription')" prop="description" min-width="200" />
        <el-table-column :label="t('design.flowColNodes')" width="100" align="right">
          <template #default="{ row }">
            <span class="tabular">{{ (row as FlowDefineItem).nodeCount ?? 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('design.flowColEdges')" width="100" align="right">
          <template #default="{ row }">
            <span class="tabular">{{ (row as FlowDefineItem).edgeCount ?? 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('design.flowColVersion')" width="90" align="center">
          <template #default="{ row }">
            <span class="mono">v{{ (row as FlowDefineItem).version }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('design.flowColStatus')" width="100" align="center">
          <template #default="{ row }">
            <span class="status" :class="(row as FlowDefineItem).status === 1 ? 'on' : 'off'">
              {{ (row as FlowDefineItem).status === 1 ? t('common.enabled') : t('common.disabled') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('design.flowColUpdatedAt')" width="160">
          <template #default="{ row }">
            <span class="mono-time">{{ formatTime((row as FlowDefineItem).updatedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" :width="WIDTH_ACTIONS" align="center" fixed="right">
          <template #default="{ row }">
            <el-tooltip v-if="auth.hasPerm('design:flow:update')" :content="t('design.flowEdit')" placement="top">
              <el-button size="small" link :icon="Edit" @click="goEdit(row as FlowDefineItem)" />
            </el-tooltip>
            <el-tooltip v-if="auth.hasPerm('design:flow:update')" :content="t('design.flowPreview')" placement="top">
              <el-button size="small" link :icon="View" @click="goPreview(row as FlowDefineItem)" />
            </el-tooltip>
            <el-tooltip v-if="auth.hasPerm('design:flow:deploy') && (row as FlowDefineItem).status !== 1" :content="t('design.flowPublish')" placement="top">
              <el-button size="small" link :icon="Promotion" @click="onPublish(row as FlowDefineItem)" />
            </el-tooltip>
            <el-tooltip v-if="auth.hasPerm('design:flow:deploy') && (row as FlowDefineItem).status === 1" :content="t('design.flowUnpublish')" placement="top">
              <el-button size="small" link :icon="CircleClose" @click="onUnpublish(row as FlowDefineItem)" />
            </el-tooltip>
            <el-dropdown v-if="auth.hasPerm('design:flow:delete')" trigger="click" @command="(cmd) => onCmd(cmd, row as FlowDefineItem)">
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
        <template #empty>
          <div class="empty">
            <el-icon class="empty-icon"><Files /></el-icon>
            <p class="muted">{{ t('design.flowEmpty') }}</p>
            <el-button v-if="auth.hasPerm('design:flow:create')" type="primary" :icon="Plus" @click="goCreate">
              {{ t('design.flowCreate') }}
            </el-button>
          </div>
        </template>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useI18n } from 'vue-i18n';
import {
  Plus, Edit, View, Delete, MoreFilled, Files,
  Promotion, CircleClose
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { flowDefineApi, type FlowDefineItem } from '@/api/flowDefine';
import { formatDateTime } from '@/utils/time';

const { t } = useI18n();
const router = useRouter();
const auth = useAuthStore();

const rows = ref<FlowDefineItem[]>([]);
const loading = ref(false);

const WIDTH_ACTIONS = 192;

function formatTime(s?: string | null) { return formatDateTime(s); }

async function loadList() {
  loading.value = true;
  try {
    rows.value = await flowDefineApi.list();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  } finally {
    loading.value = false;
  }
}

function goCreate() { router.push('/design/flow/new'); }
function goEdit(row: FlowDefineItem) { router.push(`/design/flow/${row.id}/edit`); }
function goPreview(row: FlowDefineItem) { router.push(`/design/flow/${row.id}/preview`); }

async function onPublish(row: FlowDefineItem) {
  try {
    await flowDefineApi.publish(row.id);
    ElMessage.success(t('design.flowPublished'));
    await loadList();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  }
}
async function onUnpublish(row: FlowDefineItem) {
  try {
    await flowDefineApi.unpublish(row.id);
    ElMessage.success(t('design.flowUnpublished'));
    await loadList();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  }
}

async function onCmd(cmd: string, row: FlowDefineItem) {
  if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(
        t('design.flowDeleteConfirm', { name: row.name }),
        t('common.confirmDelete'),
        { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') }
      );
      await flowDefineApi.remove(row.id);
      ElMessage.success(t('common.deleted'));
      await loadList();
    } catch { /* cancelled */ }
  }
}

onMounted(loadList);
</script>

<style scoped>
.flow-page .page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 16px;
}
.flow-page .page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; }
.flow-page .page-desc  { margin: 4px 0 0; font-size: 13px; }
.header-actions { display: flex; gap: 8px; }
.flow-table-wrap { margin-top: 16px; }
.flow-page :deep(th.el-table__cell) {
  position: sticky; top: 92px; z-index: 20;
  background: var(--color-bg) !important;
}

.status { font-size: 11px; padding: 1px 8px; border-radius: 2px; font-weight: 500; }
.status.on  { background: #e6f4ea; color: #0e8f5c; }
.status.off { background: #f0f0eb; color: #8a8a85; }

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 40px 20px;
  gap: 8px;
}
.empty-icon { font-size: 40px; color: var(--color-text-muted); }

.danger-text { color: var(--color-danger); display: inline-flex; align-items: center; gap: 4px; }
</style>