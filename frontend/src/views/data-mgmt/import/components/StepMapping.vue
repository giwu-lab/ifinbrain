<template>
  <div class="imp-step">
    <div class="map-layout">
      <!-- 左：字段映射 -->
      <div class="map-left">
        <div class="map-head">
          <span class="muted">{{ t('dataMgmtImport.mapping') }}</span>
          <span class="muted tabular">
            {{ mappedCount }} / {{ w.mappings.value.length }}
          </span>
        </div>

        <el-table :data="w.mappings.value" border size="small" max-height="360">
          <el-table-column :label="t('dataMgmtImport.colImport')" width="64" align="center">
            <template #default="{ row }">
              <el-checkbox v-model="row.enabled" />
            </template>
          </el-table-column>

          <el-table-column :label="t('dataMgmtImport.colSource')" prop="sourceHeader" min-width="150">
            <template #default="{ row }">
              <span :class="row.enabled ? '' : 'muted'">{{ row.sourceHeader }}</span>
            </template>
          </el-table-column>

          <el-table-column :label="t('dataMgmtImport.colTarget')" min-width="200">
            <template #default="{ row }">
              <el-select
                v-model="row.targetColumn"
                :disabled="!row.enabled"
                size="small"
                filterable
                clearable
                style="width: 100%"
                :placeholder="t('dataMgmtImport.notMapped')"
              >
                <el-option
                  v-for="c in w.targetColumns.value"
                  :key="c.name"
                  :value="c.name"
                  :disabled="isTaken(c.name, row as ImportMapping)"
                  :label="c.name"
                >
                  <span class="opt-name">{{ c.name }}</span>
                  <span class="opt-meta muted">{{ c.dataType }}</span>
                </el-option>
              </el-select>
            </template>
          </el-table-column>

          <el-table-column :label="t('dataMgmtImport.colSample')" min-width="130">
            <template #default="{ row }">
              <span class="muted sample">{{ sampleOf(row.sourceHeader) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 右：源文件样例数据 -->
      <div class="map-right">
        <div class="map-head">
          <span class="muted">{{ t('dataMgmtImport.sourcePreview') }}</span>
          <span class="muted tabular">{{ w.preview.value?.totalRows ?? 0 }}</span>
        </div>
        <el-table :data="sampleRows" border size="small" max-height="360" empty-text="—">
          <el-table-column
            v-for="(h, i) in w.preview.value?.headers ?? []"
            :key="i"
            :label="h"
            min-width="110"
            show-overflow-tooltip
          />
        </el-table>
      </div>
    </div>

    <el-alert
      v-if="w.preview.value?.truncated"
      type="warning"
      :closable="false"
      class="trunc-alert"
      :title="t('dataMgmtImport.truncatedWarn')"
    />

    <el-form label-width="140px" label-position="right" class="opts-form">
      <el-divider content-position="left">{{ t('dataMgmtImport.importOptions') }}</el-divider>

      <el-form-item :label="t('dataMgmtImport.optEmptyAsNull')">
        <el-switch v-model="w.options.emptyAsNull" />
        <span class="hint muted">{{ t('dataMgmtImport.optEmptyAsNullHint') }}</span>
      </el-form-item>

      <el-form-item :label="t('dataMgmtImport.optTruncate')">
        <el-switch v-model="w.options.truncateString" />
        <span class="hint muted">{{ t('dataMgmtImport.optTruncateHint') }}</span>
      </el-form-item>

      <el-form-item :label="t('dataMgmtImport.optSkipEmpty')">
        <el-switch v-model="w.options.skipEmptyRow" />
        <span class="hint muted">{{ t('dataMgmtImport.optSkipEmptyHint') }}</span>
      </el-form-item>

      <el-form-item :label="t('dataMgmtImport.optBatchSize')">
        <el-input-number
          v-model="w.options.batchSize"
          :min="200"
          :max="2000"
          :step="200"
          controls-position="right"
          style="width: 160px"
        />
        <span class="hint muted">{{ t('dataMgmtImport.optBatchSizeHint') }}</span>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import type { ImportMapping } from '@/api/dataImport';
import { useImportWizard } from '../composables/useImportWizard';

const { t } = useI18n();
const w = useImportWizard();

const mappedCount = computed(
  () => w.mappings.value.filter((m) => m.enabled && m.targetColumn).length,
);

const sampleRows = computed<string[][]>(() => w.preview.value?.sampleRows ?? []);

/** 目标列已被别的行占用就不能再选 —— 同一个列被映射两次后端会直接拒绝 */
function isTaken(name: string, self: ImportMapping): boolean {
  return w.mappings.value.some(
    (m) => m !== self && m.enabled && m.targetColumn === name,
  );
}

function sampleOf(header: string): string {
  const idx = (w.preview.value?.headers ?? []).indexOf(header);
  if (idx < 0) return '—';
  const row = sampleRows.value[0];
  const v = row?.[idx];
  return v === undefined || v === '' ? '—' : (v.length > 18 ? `${v.slice(0, 18)}…` : v);
}
</script>

<style scoped>
.imp-step { padding: 4px 0 0; }

.map-layout { display: flex; gap: 16px; }
.map-left { flex: 1 1 55%; min-width: 0; }
.map-right { flex: 1 1 45%; min-width: 0; }

.map-head {
  display: flex; justify-content: space-between; align-items: center;
  padding: 0 2px 8px; font-size: 13px;
}

.opt-name { margin-right: 10px; }
.opt-meta { float: right; font-size: 12px; }

.sample { font-size: 12px; }

.trunc-alert { margin-top: 12px; }

.opts-form { margin-top: 8px; }

.hint { margin-left: 12px; font-size: 12px; }
</style>