<template>
  <div class="page-container fade-in-up qy-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('dataMgmt.qyTitle') }}</h1>
        <p class="page-desc muted">{{ t('dataMgmt.qyDesc') }}</p>
      </div>
      <div class="header-actions">
        <el-button v-if="auth.hasPerm('data:query:create')" type="primary" @click="openNew">
          + {{ t('dataMgmt.qyCreate') }}
        </el-button>
      </div>
    </div>

    <div class="qy-panel">
      <el-table
        v-if="items.length > 0"
        :data="items"
        stripe
        v-loading="loading"
        :row-class-name="rowClass"
      >
<el-table-column :label="t('dataMgmt.qyFieldName')" min-width="180">
          <template #default="{ row }">
            <div class="qy-name-row">
              <span class="qy-name-text">{{ row.name }}</span>
              <span v-if="row.status === 0" class="qy-tag-disabled">{{ t('dataMgmt.qyStatusDisabled') }}</span>
              <span v-if="row.description" class="muted small qy-desc-inline">· {{ row.description }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('dataMgmt.qyFieldDatasource')" width="160">
          <template #default="{ row }">
            <code class="ds-name">{{ row.datasourceName }}</code>
            <span class="db-type-tag">{{ row.dbType }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('dataMgmt.colParams')" width="56" align="right">
          <template #default="{ row }">{{ row.paramsSchema.length }}</template>
        </el-table-column>
        <el-table-column :label="t('dataMgmt.colLastRun')" min-width="160">
          <template #default="{ row }">
            <span v-if="row.lastRunAt == null" class="muted small">{{ t('dataMgmt.qyNeverRun') }}</span>
            <div v-else class="run-row" :class="row.lastRunOk === 1 ? 'ok' : 'fail'">
              <span class="run-cell">
                <span class="dot" />
                <span class="small">{{ formatTime(row.lastRunAt) }}</span>
                <span v-if="row.lastRunRowCount != null" class="small muted">
                  · {{ row.lastRunRowCount }} {{ t('dataMgmt.qyUnitRow') }}
                </span>
                <span v-if="row.lastRunDurationMs != null" class="small muted">· {{ row.lastRunDurationMs }} ms</span>
              </span>
              <span v-if="row.lastRunMsg && row.lastRunOk === 0" class="err-msg small" :title="row.lastRunMsg">
                · {{ truncate(row.lastRunMsg, 80) }}
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('dataMgmt.colCreatedBy')" width="64">
          <template #default="{ row }"><span class="small muted">{{ row.createdBy }}</span></template>
        </el-table-column>
        <!-- 页面操作列：打开 / 发布 / 取消发布 / 重新发布 / 页面编辑 / 删除页面 -->
        <el-table-column :label="t('dataMgmt.qyPageActions')" min-width="360" width="380">
          <template #default="{ row }">
            <div class="qp-actions">
              <!-- 未发布：只显示 发布 -->
              <template v-if="row.publishedMenuId == null">
                <el-button v-if="auth.hasPerm('data:query:publish')"
                  link type="primary" size="small"
                  @click.stop="onPublish(row as unknown as QueryItem)">{{ t('dataMgmt.qyPublish') }}</el-button>
              </template>

              <!-- 已发布（status=1）：打开页面 / 编辑 / 取消发布 / 删除页面 -->
              <template v-else-if="row.publishedMenuStatus === 1">
                <el-button link type="primary" size="small"
                  @click.stop="onOpenPage(row as unknown as QueryItem)">{{ t('dataMgmt.qyOpenPage') }}</el-button>
                <el-button v-if="auth.hasPerm('data:query:publish')"
                  link size="small"
                  @click.stop="onEditPage(row as unknown as QueryItem)">{{ t('dataMgmt.qyEditPage') }}</el-button>
                <el-button v-if="auth.hasPerm('data:query:publish')"
                  link size="small"
                  @click.stop="onUnpublish(row as unknown as QueryItem)">{{ t('dataMgmt.qyUnpublish') }}</el-button>
                <el-button v-if="auth.hasPerm('data:query:publish')"
                  link size="small" type="danger"
                  @click.stop="onDeletePage(row as unknown as QueryItem)">{{ t('dataMgmt.qyDeletePageBtn') }}</el-button>
              </template>

              <!-- 已取消（status=0）：重新发布 / 编辑 / 删除页面 -->
              <template v-else>
                <el-button v-if="auth.hasPerm('data:query:publish')"
                  link type="primary" size="small"
                  @click.stop="onRepublish(row as unknown as QueryItem)">{{ t('dataMgmt.qyRepublish') }}</el-button>
                <el-button v-if="auth.hasPerm('data:query:publish')"
                  link size="small"
                  @click.stop="onEditPage(row as unknown as QueryItem)">{{ t('dataMgmt.qyEditPage') }}</el-button>
                <el-button v-if="auth.hasPerm('data:query:publish')"
                  link size="small" type="danger"
                  @click.stop="onDeletePage(row as unknown as QueryItem)">{{ t('dataMgmt.qyDeletePageBtn') }}</el-button>
              </template>
            </div>
          </template>
        </el-table-column>
        <!-- 操作列：编辑 / 复制（图标直显） + 删除（保留在 ··· 下拉） -->
        <el-table-column :label="t('system.colAction')" width="120" align="center" fixed="right">
          <template #default="{ row }">
            <!-- 编辑：图标直显 -->
            <el-tooltip v-if="auth.hasPerm('data:query:update')" :content="t('common.edit')" placement="top">
              <el-button size="small" link :icon="Edit" @click="onActionCommand('edit', row as unknown as QueryItem)" />
            </el-tooltip>
            <!-- 复制：图标直显 -->
            <el-tooltip v-if="auth.hasPerm('data:query:create')" :content="t('dataMgmt.qyDetailDuplicate')" placement="top">
              <el-button size="small" link :icon="CopyDocument" @click="onActionCommand('duplicate', row as unknown as QueryItem)" />
            </el-tooltip>
            <!-- 删除：保留在 ··· 下拉 -->
            <el-dropdown v-if="auth.hasPerm('data:query:delete')" trigger="click" @command="(c: string) => onActionCommand(c, row as unknown as QueryItem)">
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

      <div v-else-if="!loading" class="qy-empty">
        <div class="qy-empty-icon" />
        <h3>{{ t('dataMgmt.qyEmptyTitle') }}</h3>
        <p class="muted small">{{ t('dataMgmt.qyEmptyDesc') }}</p>
        <el-button v-if="auth.hasPerm('data:query:create')" type="primary" plain @click="openNew">
          {{ t('dataMgmt.qyEmptyAction') }}
        </el-button>
      </div>
    </div>

    <PublishDialog
      v-model="publishOpen"
      :queries="items"
      :publishing="publishing"
      :mode="publishMode"
      :target="publishTarget"
      :query-id="publishQueryId"
      @submit="onPublishSubmit"
      @update="onPublishUpdate" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useI18n } from 'vue-i18n';
import { Edit, Delete, MoreFilled, CopyDocument } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { queryApi, type QueryItem } from '@/api/query';
import { queryPageApi, parseConfig, type QueryPageConfig } from '@/api/queryPage';
import PublishDialog, { type EditTarget } from './PublishDialog.vue';

const { t } = useI18n();
const router = useRouter();
const auth = useAuthStore();

const items = ref<QueryItem[]>([]);
const loading = ref(false);


function rowClass({ row }: { row: QueryItem }) {
  return row.lastRunOk === 0 ? 'qy-row-fail' : '';
}

function openNew() {
  router.push('/data-mgmt/query/new');
}

function onEdit(row: QueryItem) {
  router.push(`/data-mgmt/query/${row.id}`);
}

async function onDuplicate(row: QueryItem) {
  try {
    const detail = await queryApi.detail(row.id);
    const r = await queryApi.create({
      name: detail.name + ' (copy)',
      datasourceId: detail.datasourceId,
      sqlTemplate: detail.sqlTemplate,
      paramsSchema: detail.paramsSchema,
      status: 1,
    });
    ElMessage.success(t('dataMgmt.qyDetailDuplicated'));
    router.push(`/data-mgmt/query/${r.id}`);
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || t('dataMgmt.qyDetailDuplicatedFail'));
  }
}

async function onDelete(row: QueryItem) {
  try {
    await ElMessageBox.confirm(t('dataMgmt.qyDeleteConfirm', { name: row.name }), t('common.confirmDelete'), {
      confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel'),
      type: 'warning',
    });
    await queryApi.remove(row.id);
    ElMessage.success(t('common.deleted'));
    await loadList();
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e?.message || t('dataMgmt.qyDeleteFail'));
  }
}

function onActionCommand(cmd: string, row: QueryItem) {
  if (cmd === 'edit') onEdit(row);
  else if (cmd === 'duplicate') onDuplicate(row);
  else if (cmd === 'delete') onDelete(row);
}

// ===== 页面发布相关 =====

const publishOpen = ref(false);
const publishMode = ref<'create' | 'edit'>('create');
const publishTarget = ref<EditTarget | null>(null);
const publishQueryId = ref<number | null>(null);
const publishing = ref(false);

async function onPublish(row: QueryItem) {
  publishMode.value = 'create';
  publishTarget.value = null;
  publishQueryId.value = row.id;
  publishOpen.value = true;
}

async function onEditPage(row: QueryItem) {
  publishMode.value = 'edit';
  publishTarget.value = {
    menuId: row.publishedMenuId!,
    menuName: row.publishedMenuName || row.name,
    queryId: row.id,
    icon: row.publishedMenuIcon || 'Document',
    sort: row.publishedMenuSort ?? 99,
    config: parseConfig(undefined), // PublishDialog 会自己 fetch detail
  };
  publishOpen.value = true;
}

async function onOpenPage(row: QueryItem) {
  if (!row.publishedMenuPath) return;
  router.push(row.publishedMenuPath);
}

async function onUnpublish(row: QueryItem) {
  try {
    await ElMessageBox.confirm(
      t('dataMgmt.qyConfirmUnpublish', { name: row.publishedMenuName || row.name }),
      '',
      { type: 'warning', confirmButtonText: t('dataMgmt.qyUnpublish'), cancelButtonText: t('common.cancel') }
    );
  } catch { return; }
  try {
    await queryPageApi.unpublish(row.id);
    ElMessage.success(t('dataMgmt.qyUnpublished'));
    await loadList();
    await auth.fetchMe();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('dataMgmt.qyUnpublishFailed'));
  }
}

async function onRepublish(row: QueryItem) {
  try {
    await queryPageApi.republish(row.id);
    ElMessage.success(t('dataMgmt.qyRepublished'));
    await loadList();
    await auth.fetchMe();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('dataMgmt.qyRepublishFailed'));
  }
}

async function onDeletePage(row: QueryItem) {
  try {
    await ElMessageBox.confirm(
      t('dataMgmt.qyConfirmDeletePage', { name: row.publishedMenuName || row.name }),
      '',
      {
        type: 'error',
        confirmButtonText: t('dataMgmt.qyDeletePage'),
        cancelButtonText: t('common.cancel'),
        confirmButtonClass: 'el-button--danger',
      }
    );
  } catch { return; }
  try {
    await queryPageApi.deletePage(row.id);
    ElMessage.success(t('dataMgmt.qyPageDeleted'));
    await loadList();
    await auth.fetchMe();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('dataMgmt.qyDeletePageFailed'));
  }
}

async function onPublishSubmit(payload: { queryId: number; menuName: string; icon: string; sort: number; configJson: string }) {
  publishing.value = true;
  try {
    await queryPageApi.publish(payload.queryId, payload.menuName, payload.configJson, payload.icon, payload.sort);
    publishOpen.value = false;
    ElMessage.success(t('dataMgmt.qyPublished'));
    await loadList();
    await auth.fetchMe();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('dataMgmt.qyPublishFailed'));
  } finally {
    publishing.value = false;
  }
}

async function onPublishUpdate(payload: { menuId: number; menuName: string; icon: string; sort: number; configJson: string }) {
  publishing.value = true;
  try {
    // queryId 还是要从 row 找（dialog 没传）。简化：PublishDialog 已经有 menuId，从 menuId 找 queryId
    const row = items.value.find(q => q.publishedMenuId === payload.menuId);
    if (!row) throw new Error('Query not found');
    await queryPageApi.updatePage(row.id, payload.menuName, payload.configJson, payload.icon, payload.sort);
    publishOpen.value = false;
    ElMessage.success(t('dataMgmt.qyPageUpdated'));
    await loadList();
    await auth.fetchMe();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('dataMgmt.qyPageUpdateFailed'));
  } finally {
    publishing.value = false;
  }
}

// ===== util =====

function formatTime(s: string) {
  if (!s) return '';
  const d = new Date(s);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
function truncate(s: string | null | undefined, n: number) {
  if (!s) return '';
  return s.length > n ? s.substring(0, n) + '…' : s;
}

async function loadList() {
  loading.value = true;
  try {
    items.value = await queryApi.list();
  } finally {
    loading.value = false;
  }
}

onMounted(loadList);
</script>

<style scoped>
.qy-page .page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 24px; margin-bottom: 24px; }
.qy-page .page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; }
.qy-page .page-desc { margin: 4px 0 0; font-size: 13px; }

.qy-panel {
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: #fff;
  overflow: hidden;
}

.qy-name-row { display: flex; align-items: center; gap: 8px; min-width: 0; }
.qy-name-text { font-weight: 600; color: var(--color-text); white-space: nowrap; }
.qy-tag-disabled { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--color-warning, #f0a020); color: #fff; flex-shrink: 0; }
.qy-desc-inline { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; flex: 1; }
.ds-name {
  font-family: ui-monospace, 'JetBrains Mono', monospace;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100px;
}
.db-type-tag {
  font-size: 11px; padding: 1px 6px; border-radius: 4px;
  background: var(--bg-soft, #f5f7fa); color: var(--color-text-muted, #909399);
  margin-left: 6px;
  white-space: nowrap;
  flex-shrink: 0;
}

/* 页面状态徽章 */
.qy-page-status-on {
  font-size: 11px; padding: 1px 8px; border-radius: 2px;
  background: #E6F4EA; color: #0E8F5C;
  font-weight: 500;
}
.qy-page-status-off {
  font-size: 11px; padding: 1px 8px; border-radius: 2px;
  background: #F0F0EB; color: #8A8A85;
  font-weight: 500;
}

/* 页面操作列按钮容器 */
.qp-actions {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 0 4px;
  align-items: center;
  max-width: 100%;
}

.run-row { display: flex; align-items: center; gap: 4px; min-width: 0; }
.run-cell { display: inline-flex; gap: 4px; align-items: center; flex-shrink: 0; }
.run-row .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--color-success, #67c23a); flex-shrink: 0; }
.run-row.fail .dot { background: var(--color-danger, #f56c6c); }
.err-msg { color: var(--color-danger, #f56c6c); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; flex: 1; }

:deep(.qy-row-fail td) { background: #fcf6f6 !important; }

.qy-empty { padding: 80px 24px; text-align: center; }
.qy-empty h3 { margin: 12px 0 4px; font-size: 16px; }
.qy-empty-icon { width: 56px; height: 56px; margin: 0 auto; border: 2px dashed var(--line); border-radius: 50%; }

.qy-more-btn { font-size: 16px; padding: 0 6px; }
</style>