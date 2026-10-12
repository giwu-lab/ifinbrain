<template>
  <div class="imp-step">
    <!-- 执行结果：优先级最高，成功后盖住预检 -->
    <div v-if="result" class="result-box" :class="`st-${result.status}`">
      <div class="result-head">
        <el-icon class="result-icon">
          <CircleCheckFilled v-if="result.status === 0" />
          <WarningFilled v-else-if="result.status === 1" />
          <CircleCloseFilled v-else />
        </el-icon>
        <span class="result-title">{{ result.message }}</span>
        <span class="muted mono-time">{{ formatMs(result.durationMs) }}</span>
      </div>

      <div class="result-stats">
        <div class="stat">
          <span class="stat-num tabular">{{ result.totalRows }}</span>
          <span class="stat-label">{{ t('dataMgmtImport.totalRows') }}</span>
        </div>
        <div class="stat">
          <span class="stat-num tabular ok">{{ result.insertedRows }}</span>
          <span class="stat-label">{{ t('dataMgmtImport.insertedRows') }}</span>
        </div>
        <div class="stat">
          <span class="stat-num tabular" :class="{ warn: result.skippedRows > 0 }">
            {{ result.skippedRows }}
          </span>
          <span class="stat-label">{{ t('dataMgmtImport.skippedRows') }}</span>
        </div>
      </div>

      <div class="result-actions">
        <el-button @click="startOver">{{ t('dataMgmtImport.startOver') }}</el-button>
      </div>
    </div>

    <!-- 预检结果 -->
    <template v-else>
      <div class="check-box">
        <div class="check-row">
          <span class="muted">{{ t('dataMgmtImport.target') }}</span>
          <code class="target-full">{{ targetLabel }}</code>
        </div>
        <div class="check-row">
          <span class="muted">{{ t('dataMgmtImport.targetCurrentRows') }}</span>
          <span class="tabular">
            {{ vr && vr.targetRowCount >= 0 ? vr.targetRowCount : t('dataMgmtImport.unknown') }}
          </span>
        </div>
        <div class="check-row">
          <span class="muted">{{ t('dataMgmtImport.fileRows') }}</span>
          <span class="tabular">{{ vr?.totalRows ?? '—' }}</span>
        </div>
      </div>

      <div v-if="vr" class="stat-row">
        <div class="stat">
          <span class="stat-num tabular ok">{{ vr.wouldInsert }}</span>
          <span class="stat-label">{{ t('dataMgmtImport.willInsert') }}</span>
        </div>
        <div class="stat">
          <span class="stat-num tabular" :class="{ danger: vr.errorRowCount > 0 }">
            {{ vr.errorRowCount }}
          </span>
          <span class="stat-label">{{ t('dataMgmtImport.badRows') }}</span>
        </div>
        <div class="stat">
          <span class="stat-num tabular" :class="{ warn: vr.warningCount > 0 }">
            {{ vr.warningCount }}
          </span>
          <span class="stat-label">{{ t('dataMgmtImport.fixedCells') }}</span>
        </div>
      </div>

      <el-alert
        v-if="vr && !vr.valid"
        type="error"
        :closable="false"
        class="alert"
        :title="t('dataMgmtImport.nothingValid')"
      />
      <el-alert
        v-else-if="vr && vr.errorRowCount > 0"
        type="warning"
        :closable="false"
        class="alert"
        :title="t('dataMgmtImport.partialWarn', { rows: vr.errorRowCount })"
      />

      <!-- 错误明细：测试反馈就地展示，不弹 toast 轰炸 -->
      <div v-if="vr && vr.errorRows.length" class="err-box">
        <div class="err-head">
          <span class="muted">{{ t('dataMgmtImport.errorDetail') }}</span>
          <el-button link type="primary" @click="downloadErrors">
            {{ t('dataMgmtImport.downloadErrors') }}
          </el-button>
        </div>
        <el-table :data="vr.errorRows" border stripe size="small" max-height="260">
          <el-table-column :label="t('dataMgmtImport.colRow')" prop="rowNo" width="70" align="right">
            <template #default="{ row }"><span class="tabular">{{ row.rowNo }}</span></template>
          </el-table-column>
          <el-table-column :label="t('dataMgmtImport.colField')" prop="field" width="140" show-overflow-tooltip />
          <el-table-column :label="t('dataMgmtImport.colValue')" prop="rawValue" min-width="150" show-overflow-tooltip />
          <el-table-column :label="t('dataMgmtImport.colReason')" prop="reason" min-width="220" show-overflow-tooltip />
        </el-table>
      </div>

      <div class="exec-bar">
        <div class="exec-hint muted">
          <template v-if="canExecute">{{ t('dataMgmtImport.execHint') }}</template>
          <template v-else>{{ t('dataMgmtImport.noExecutePerm') }}</template>
        </div>
        <el-button
          v-if="canExecute"
          type="primary"
          :icon="UploadFilled"
          :loading="w.committing.value"
          @click="confirmAndCommit"
        >
          {{ t('dataMgmtImport.execute') }}
        </el-button>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { UploadFilled } from '@element-plus/icons-vue';
import { useI18n } from 'vue-i18n';
import { useAuthStore } from '@/stores/auth';
import type { ImportErrorRow } from '@/api/dataImport';
import { useImportWizard, useTargetLabel } from '../composables/useImportWizard';

const { t } = useI18n();
const auth = useAuthStore();
const w = useImportWizard();

const targetLabel = useTargetLabel();
const vr = computed(() => w.validateResult.value);
const result = computed(() => w.commitResult.value);

const canExecute = computed(
  () => auth.hasPerm('data:import:execute') && !!vr.value?.valid,
);

function formatMs(ms: number): string {
  if (ms < 1000) return `${ms} ms`;
  return `${(ms / 1000).toFixed(1)} s`;
}

/** 回车即触发 —— 用户改了映射后不需要再点一次「预检」 */
async function confirmAndCommit() {
  const ok = await w.doValidate();
  if (!ok) return;
  const v = w.validateResult.value;
  if (!v) return;
  try {
    await ElMessageBox.confirm(
      t('dataMgmtImport.confirmText', {
        table: targetLabel.value,
        rows: v.wouldInsert,
        current: v.targetRowCount >= 0 ? v.targetRowCount : '?',
      }),
      t('dataMgmtImport.confirmTitle'),
      { type: 'warning', confirmButtonText: t('dataMgmtImport.execute'),
        cancelButtonText: t('common.cancel') },
    );
  } catch {
    return;   // 用户取消
  }
  const done = await w.doCommit();
  if (done) ElMessage.success(w.commitResult.value?.message ?? '');
}

function startOver() {
  w.resetAll();
}

/** 错误明细导出 CSV（BOM 让 Excel 打开中文不乱码） */
function downloadErrors() {
  const rows = vr.value?.errorRows ?? [];
  if (!rows.length) return;
  const esc = (v: unknown) => {
    const s = String(v ?? '');
    // 防 CSV formula injection：以下前缀会被 Excel 当公式执行
    const safe = /^[=+\-@\t\r]/.test(s) ? `'${s}` : s;
    return /[",\n]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
  };
  const lines = [
    [t('dataMgmtImport.colRow'), t('dataMgmtImport.colField'),
     t('dataMgmtImport.colValue'), t('dataMgmtImport.colReason')].map(esc).join(','),
  ];
  for (const r of rows as ImportErrorRow[]) {
    lines.push([r.rowNo, r.field, r.rawValue, r.reason].map(esc).join(','));
  }
  const blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `import-errors-${Date.now()}.csv`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(a.href);
}
</script>

<style scoped>
.imp-step { padding: 4px 0 0; }

.check-box {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-elev);
  padding: 12px 14px;
}
.check-row {
  display: flex; justify-content: space-between;
  gap: 16px; padding: 4px 0; font-size: 13px;
}

.target-full {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--color-text);
}

.stat-row { display: flex; gap: 28px; margin-top: 16px; }
.stat { display: flex; flex-direction: column; }
.stat-num { font-size: 24px; font-weight: 700; line-height: 1.2; }
.stat-num.ok { color: var(--color-success); }
.stat-num.warn { color: var(--color-warning); }
.stat-num.danger { color: var(--color-danger); }
.stat-label { font-size: 12px; color: var(--color-text-muted); margin-top: 2px; }

.result-box {
  border: 1px solid var(--color-border);
  border-left: 3px solid var(--color-success);
  border-radius: var(--radius-sm);
  background: var(--color-bg-elev);
  padding: 16px 18px;
}
.result-box.st-1 { border-left-color: var(--color-warning); }
.result-box.st-2 { border-left-color: var(--color-danger); }

.result-head { display: flex; align-items: center; gap: 10px; }
.result-icon { font-size: 20px; color: var(--color-success); }
.st-1 .result-icon { color: var(--color-warning); }
.st-2 .result-icon { color: var(--color-danger); }
.result-title { font-weight: 600; font-size: 15px; }
.result-head .mono-time { margin-left: auto; }

.result-stats { display: flex; gap: 28px; margin-top: 14px; }
.result-actions { margin-top: 16px; }

.alert { margin-top: 16px; }

.err-box { margin-top: 16px; }
.err-head {
  display: flex; justify-content: space-between; align-items: center;
  padding: 0 2px 8px; font-size: 13px;
}

.exec-bar {
  display: flex; justify-content: flex-end; align-items: center;
  gap: 16px; margin-top: 20px;
  padding-top: 16px; border-top: 1px solid var(--color-border-soft);
}
.exec-hint { font-size: 12px; margin-right: auto; }
</style>