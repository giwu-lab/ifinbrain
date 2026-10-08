<template>
  <div class="imp-history">
    <div class="table-wrap hist-table-wrap">
      <el-table :data="rows" v-loading="loading" border stripe size="small" empty-text="—">
        <el-table-column :label="t('dataMgmtImport.colTime')" width="170">
          <template #default="{ row }">
            <span class="mono-time">{{ formatTime((row as ImportLogItem).createdAt) }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('dataMgmtImport.colDatasource')" prop="datasourceName" min-width="120" show-overflow-tooltip />

        <el-table-column :label="t('dataMgmtImport.colTarget')" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <code class="target-cell">
              {{ (row as ImportLogItem).targetSchema }}.{{ (row as ImportLogItem).targetTable }}
            </code>
          </template>
        </el-table-column>

        <el-table-column :label="t('dataMgmtImport.colFile')" prop="fileName" min-width="160" show-overflow-tooltip />

        <el-table-column :label="t('dataMgmtImport.colRows')" width="150" align="right">
          <template #default="{ row }">
            <span class="tabular">
              <span class="ok">{{ row.insertedRows ?? 0 }}</span>
              <span v-if="(row.skippedRows ?? 0) > 0" class="muted"> / {{ row.skippedRows }}</span>
            </span>
          </template>
        </el-table-column>

        <el-table-column :label="t('dataMgmtImport.colDuration')" width="100" align="right">
          <template #default="{ row }">
            <span class="tabular muted">{{ row.durationMs != null ? formatMs(row.durationMs) : '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('dataMgmtImport.colStatus')" width="100" align="center">
          <template #default="{ row }">
            <span class="st-tag" :class="`st-${row.status}`">{{ statusLabel(row.status) }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('dataMgmtImport.colOperator')" prop="operatorName" width="110" show-overflow-tooltip />

        <el-table-column :label="t('common.actions')" width="96" align="center" fixed="right">
          <template #default="{ row }">
            <el-tooltip :content="t('dataMgmtImport.viewDetail')" placement="top">
              <el-button size="small" link :icon="View" @click="openDetail(row as ImportLogItem)" />
            </el-tooltip>
            <el-dropdown
              v-if="auth.hasPerm('data:import:log:delete')"
              trigger="click"
              @command="(cmd: string) => onCmd(cmd, row as ImportLogItem)"
            >
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
    </div>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next"
        background
        @current-change="load"
      />
    </div>

    <el-dialog v-model="detailVisible" :title="t('dataMgmtImport.detailTitle')" width="640px" append-to-body>
      <el-descriptions v-if="detail" :column="2" size="small" label-class-name="extra-info-col">
        <el-descriptions-item :label="t('dataMgmtImport.colDatasource')">{{ detail.datasourceName }}</el-descriptions-item>
        <el-descriptions-item :label="t('dataMgmtImport.colTarget')">
          {{ detail.targetSchema }}.{{ detail.targetTable }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('dataMgmtImport.colFile')">{{ detail.fileName }}</el-descriptions-item>
        <el-descriptions-item :label="t('dataMgmtImport.colSheet')">{{ detail.sheetName || '—' }}</el-descriptions-item>
        <el-descriptions-item :label="t('dataMgmtImport.colTime')">{{ formatTime(detail.createdAt) }}</el-descriptions-item>
        <el-descriptions-item :label="t('dataMgmtImport.colOperator')">{{ detail.operatorName || '—' }}</el-descriptions-item>
        <el-descriptions-item :label="t('dataMgmtImport.colDuration')">
          {{ detail.durationMs != null ? formatMs(detail.durationMs) : '—' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('dataMgmtImport.colStatus')">
          {{ statusLabel(detail.status) }}
        </el-descriptions-item>
      </el-descriptions>

      <template v-if="detail?.errorMessage">
        <el-alert type="warning" :closable="false" class="detail-alert" :title="detail.errorMessage" />
      </template>

      <el-table
        v-if="detail?.errorRows?.length"
        :data="detail.errorRows"
        border
        stripe
        size="small"
        max-height="260"
        class="detail-errs"
      >
        <el-table-column :label="t('dataMgmtImport.colRow')" prop="rowNo" width="70" align="right" />
        <el-table-column :label="t('dataMgmtImport.colField')" prop="field" width="130" show-overflow-tooltip />
        <el-table-column :label="t('dataMgmtImport.colValue')" prop="rawValue" min-width="140" show-overflow-tooltip />
        <el-table-column :label="t('dataMgmtImport.colReason')" prop="reason" min-width="200" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Delete, MoreFilled, View } from '@element-plus/icons-vue';
import { useI18n } from 'vue-i18n';
import { useAuthStore } from '@/stores/auth';
import { dataImportApi } from '@/api/dataImport';
import type { ImportLogItem } from '@/api/dataImport';

const { t } = useI18n();
const auth = useAuthStore();

/** 与 data-mgmt/datasource 等页保持同一套时间格式（本项目没有公共 format 工具） */
function formatTime(s?: string | null): string {
  if (!s) return '—';
  const d = new Date(s);
  if (isNaN(d.getTime())) return s;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

const rows = ref<ImportLogItem[]>([]);
const total = ref(0);
const page = ref(1);
const size = ref(20);
const loading = ref(false);

const detailVisible = ref(false);
const detail = ref<ImportLogItem | null>(null);

async function load() {
  loading.value = true;
  try {
    const r = await dataImportApi.history({ page: page.value, size: size.value });
    rows.value = r.items ?? [];
    total.value = r.total ?? 0;
  } finally {
    loading.value = false;
  }
}

async function openDetail(row: ImportLogItem) {
  detail.value = await dataImportApi.detail(row.id);
  detailVisible.value = true;
}

async function onCmd(cmd: string, row: ImportLogItem) {
  if (cmd !== 'delete') return;
  try {
    await ElMessageBox.confirm(
      t('dataMgmtImport.confirmDeleteLog', { table: `${row.targetSchema}.${row.targetTable}` }),
      t('common.confirmDelete'),
      {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel'),
      },
    );
  } catch {
    return;
  }
  await dataImportApi.remove(row.id);
  ElMessage.success(t('common.success'));
  if (rows.value.length === 1 && page.value > 1) page.value -= 1;
  await load();
}

function statusLabel(s: number): string {
  if (s === 0) return t('dataMgmtImport.statusOk');
  if (s === 1) return t('dataMgmtImport.statusPartial');
  return t('dataMgmtImport.statusFailed');
}

function formatMs(ms: number): string {
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
}

defineExpose({ load });

onMounted(load);
</script>

<style scoped>
.imp-history { padding: 4px 0 0; }

.target-cell {
  font-family: 'SF Mono', 'JetBrains Mono', Menlo, monospace;
  font-size: 13px;
}

.ok { color: var(--color-success); }

.st-tag { font-size: 12px; padding: 1px 7px; border-radius: 3px; }
.st-0 { color: var(--color-success); background: var(--color-success-soft); }
.st-1 { color: var(--color-warning); background: var(--color-warning-soft); }
.st-2 { color: var(--color-danger);  background: var(--color-danger-soft); }

.pager { display: flex; justify-content: flex-end; margin-top: 14px; }

.detail-alert { margin-top: 14px; }
.detail-errs { margin-top: 14px; }
</style>