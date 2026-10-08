<template>
  <div class="imp-step">
    <el-form label-width="110px" label-position="right">
      <el-form-item :label="t('dataMgmtImport.datasource')" required>
        <el-select
          v-model="w.datasourceId.value"
          :loading="w.dsLoading.value"
          style="width: 320px"
          placeholder=""
          @change="onDsChange"
        >
          <el-option
            v-for="d in w.datasources.value"
            :key="d.id"
            :value="d.id"
            :label="d.name"
          >
            <span class="opt-name">{{ d.name }}</span>
            <span class="opt-meta muted tabular">{{ d.dbType }} · {{ d.databaseName }}</span>
          </el-option>
        </el-select>
      </el-form-item>

      <el-form-item :label="t('dataMgmtImport.targetTable')" required>
        <el-select
          v-model="tableKey"
          :disabled="!w.datasourceId.value"
          :loading="w.schemaLoading.value"
          filterable
          style="width: 420px"
          placeholder=""
          @change="onTableChange"
        >
          <el-option
            v-for="opt in tableOptions"
            :key="opt.value"
            :value="opt.value"
            :label="opt.label"
          />
        </el-select>
      </el-form-item>

      <el-form-item :label="t('dataMgmtImport.template')">
        <el-button
          :disabled="!canDownloadTemplate"
          :loading="tplLoading"
          @click="onDownloadTemplate"
        >
          {{ t('dataMgmtImport.downloadTemplate') }}
        </el-button>
        <span class="hint muted">{{ t('dataMgmtImport.templateHint') }}</span>
      </el-form-item>
    </el-form>

    <!-- 目标表列清单：用户在这里就该看出「有没有自增列 / 有没有必填列」 -->
    <div v-if="selectedColumns.length" class="cols-box">
      <div class="cols-head">
        <span class="muted">{{ t('dataMgmtImport.targetColumns') }}</span>
        <span class="muted tabular">{{ selectedColumns.length }}</span>
      </div>
      <el-table :data="selectedColumns" border stripe size="small" max-height="300" empty-text="—">
        <el-table-column :label="t('dataMgmtImport.colColumn')" prop="name" min-width="140">
          <template #default="{ row }">
            <code class="mono-col">{{ (row as ImportColumn).name }}</code>
          </template>
        </el-table-column>
        <el-table-column :label="t('dataMgmtImport.colType')" prop="dataType" width="130">
          <template #default="{ row }">
            <span class="muted">{{ row.dataType }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('dataMgmtImport.colNullable')" width="90" align="center">
          <template #default="{ row }">
            <span v-if="row.generated" class="gen-tag">{{ t('dataMgmtImport.generated') }}</span>
            <span v-else :class="row.nullable ? 'muted' : 'req'">
              {{ row.nullable ? t('dataMgmtImport.yes') : t('dataMgmtImport.no') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('dataMgmtImport.colLength')" width="120" align="right">
          <template #default="{ row }">
            <span class="tabular muted">{{ sizeText(row as ImportColumn) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-empty
      v-else-if="w.datasourceId.value && !w.schemaLoading.value"
      :description="t('dataMgmtImport.noColumns')"
      :image-size="70"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { useI18n } from 'vue-i18n';
import { dataImportApi } from '@/api/dataImport';
import type { ImportColumn } from '@/api/dataImport';
import { useImportWizard } from '../composables/useImportWizard';

const { t } = useI18n();
const w = useImportWizard();

const tableKey = ref('');
const tplLoading = ref(false);

/** 表下拉：schema 同名的表合并成一组，避免库里几百张表把下拉撑爆 */
const tableOptions = computed(() => {
  const dto = w.schemaDto.value;
  if (!dto) return [];
  return dto.tables
    .filter((tb) => (tb.columns ?? []).length > 0)
    .map((tb) => ({ label: `${tb.schema}.${tb.simpleName}`, value: `${tb.schema}|${tb.simpleName}` }));
});

const selectedColumns = computed<ImportColumn[]>(() => {
  const dto = w.schemaDto.value;
  if (!dto || !w.table.value) return [];
  const hit = dto.tables.find(
    (tb) => tb.schema === w.schema.value && tb.simpleName === w.table.value,
  );
  return (hit?.columns ?? []) as ImportColumn[];
});

const canDownloadTemplate = computed(() => !!w.datasourceId.value && !!w.table.value);

function sizeText(c: ImportColumn): string {
  if (c.maxLength != null) return `${c.maxLength}`;
  if (c.precision != null) return c.scale != null ? `${c.precision},${c.scale}` : `${c.precision}`;
  return '—';
}

function onDsChange(id: number) {
  void w.invalidateSource();
  void w.loadSchema(id);
}

function onTableChange(v: string) {
  const [s, tb] = v.split('|');
  w.schema.value = s;
  w.table.value = tb;
  void w.invalidateSource();
}

async function onDownloadTemplate() {
  if (!w.datasourceId.value || !w.table.value) return;
  tplLoading.value = true;
  try {
    const qs = new URLSearchParams({
      datasourceId: String(w.datasourceId.value),
      schema: w.schema.value,
      table: w.table.value,
    });
    const token = localStorage.getItem('accessToken') ?? '';
    const r = await fetch(`/api/datamgmt/imports/template?${qs.toString()}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!r.ok) {
      let msg = `HTTP ${r.status}`;
      try {
        const j = await r.json();
        msg = j?.message || msg;
      } catch {
        /* 非 JSON 响应（网关错误页）时保留 HTTP 状态码 */
      }
      throw new Error(msg);
    }
    const cd = r.headers.get('Content-Disposition') || '';
    const m = cd.match(/filename="?([^";]+)"?/);
    const blob = await r.blob();
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = m ? decodeURIComponent(m[1]) : `${w.table.value}-template.xlsx`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(a.href);
  } catch (e: any) {
    ElMessage.error(e?.message || t('dataMgmtImport.templateFailed'));
  } finally {
    tplLoading.value = false;
  }
}

// 数据源换了要清掉旧的目标表选择
watch(w.datasourceId, (nv, ov) => {
  if (ov != null && nv !== ov) tableKey.value = '';
});
</script>

<style scoped>
.imp-step { padding: 4px 0 0; }

.opt-name { margin-right: 12px; }
.opt-meta { float: right; font-size: 12px; }

.hint { margin-left: 12px; font-size: 12px; }

.cols-box { margin-top: 8px; }
.cols-head {
  display: flex; justify-content: space-between; align-items: center;
  padding: 0 2px 8px; font-size: 13px;
}

.mono-col {
  font-family: 'SF Mono', 'JetBrains Mono', Menlo, monospace;
  font-size: 13px;
}

.req { color: var(--color-warning); font-size: 12px; }

.gen-tag {
  font-size: 11px; padding: 1px 6px; border-radius: 3px;
  color: var(--color-info); background: var(--color-info-soft);
}
</style>