<template>
  <div class="page-container fade-in-up">
    <div class="page-header">
      <div class="header-left">
        <button class="back-btn" @click="router.push('/data-mgmt/query')">
          <span class="back-arrow">←</span>
        </button>
        <div>
          <h1 class="page-title">{{ query?.name || t('dataMgmt.qpLoading') }}</h1>
          <p v-if="query?.description" class="page-desc muted">{{ query.description }}</p>
        </div>
      </div>
      <span class="stats muted mono small" v-if="result">
        {{ result.rowCount }} {{ t('dataMgmt.qpRows') }} ·
        {{ result.durationMs }} ms
        <span v-if="result.truncated" class="warn">
          · {{ t('dataMgmt.qpTruncated', { limit: result.rowLimit }) }}
        </span>
      </span>
      <span v-if="runError" class="err small">{{ runError }}</span>
      <span v-else-if="hasMissingRequired" class="warn small">
        {{ t('dataMgmt.qpMissingRequired') }}
      </span>
    </div>

    <el-card class="card-table" shadow="never">
      <div class="toolbar" v-if="query">
        <template v-for="p in query.paramsSchema" :key="p.name">
          <span class="field">
            <span class="label">{{ paramLabels[p.name] || p.label || p.name }}<span v-if="p.required" class="required">*</span>：</span>
            <el-input
              v-if="!p.type || p.type === 'string'"
              v-model="paramValues[p.name]" style="width: 200px"
              clearable :placeholder="p.placeholder || ''"
              @input="scheduleReload(300)" />
            <el-input
              v-else-if="p.type === 'text'" type="textarea" :rows="2"
              v-model="paramValues[p.name]" style="width: 260px"
              @input="scheduleReload(500)" />
            <el-date-picker
              v-else-if="p.type === 'date'"
              v-model="paramValues[p.name]" type="date" value-format="YYYY-MM-DD"
              style="width: 160px" :placeholder="p.placeholder || ''"
              @change="reload" />
            <el-date-picker
              v-else-if="p.type === 'datetime'"
              v-model="paramValues[p.name]" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
              style="width: 220px" :placeholder="p.placeholder || ''"
              @change="reload" />
            <el-input-number
              v-else-if="p.type === 'number'"
              v-model="paramValues[p.name]" :controls="false"
              style="width: 140px" @change="reload" />
          </span>
        </template>

        <span class="toolbar-right">
          <el-button :icon="Refresh" @click="resetQuery">{{ t('common.reset') }}</el-button>
          <el-dropdown
            split-button
            type="success"
            :loading="exporting"
            @click="onExport('csv')"
            @command="onExport"
          >
            {{ t('dataMgmt.qpExportCsv') }}
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="csv">{{ t('dataMgmt.qpExportCsv') }}</el-dropdown-item>
                <el-dropdown-item command="xlsx">{{ t('dataMgmt.qpExportXlsx') }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </span>
      </div>

      <el-table
        :data="pagedRows"
        v-loading="loading"
        border
        stripe
        size="small"
        :max-height="tableHeight"
        :column-resizable="true"
        @header-dragend="onColumnResize"
        empty-text="—"
      >
        <el-table-column
          v-for="col in displayColumns"
          :key="col.key"
          :column-key="col.key"
          :prop="col.key"
          :label="col.label || col.key"
          :width="widthOf(col.key, serverWidthOf(col.key, autoWidth(col.label || col.key)))"
          :min-width="80"
          :align="col.type === 'number' ? 'right' : 'left'"
        >
          <template #default="{ row }">
            <span class="cell-text">{{ formatCell(row[col.key]) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="!result || result.rows.length === 0" class="empty-hint muted" v-show="!loading">
        <template v-if="hasMissingRequired">
          {{ t('dataMgmt.qpMissingRequiredHint') }}
        </template>
        <template v-else>
          {{ t('dataMgmt.qpEmptyResult') }}
        </template>
      </div>

      <el-pagination
        v-if="result && result.rows.length > 0"
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :total="result.rows.length"
        :page-sizes="[20, 50, 100, 200]"
        layout="total,sizes,prev,pager,next,jumper"
        class="qp-pager"
        @current-change="onPagerChange"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { queryApi, type QueryItem, type QueryResult } from '@/api/query';
import { queryPageApi, parseConfig, type QueryPageConfig } from '@/api/queryPage';
import { useColumnWidths } from '@/composables/useColumnWidths';

const { t } = useI18n();
const route = useRoute();
const router = useRouter();

const menuId = computed(() => Number(route.params.menuId));

const queryId = ref<number | null>(null);
const query = ref<QueryItem | null>(null);
const paramValues = ref<Record<string, any>>({});
const result = ref<QueryResult | null>(null);
const loading = ref(false);
const exporting = ref(false);
const runError = ref('');
const page = ref(1);
const pageSize = ref(50);

const config = ref<QueryPageConfig>({ paramLabels: {}, columns: {} });
const paramLabels = computed(() => config.value.paramLabels);

/** 服务器存的列宽（用户发布时设的）；用户拖动列宽后由 localStorage 覆盖 */
const serverWidths = ref<Record<string, number>>({});
function serverWidthOf(colKey: string, fallback: number): number {
  return serverWidths.value[colKey] ?? fallback;
}

const { widthOf, onResize: onColumnResize } = useColumnWidths(`query-page.${menuId.value}`);

const displayColumns = computed(() => result.value?.columns || []);
const displayRows = computed(() => result.value?.rows || []);

/**
 * 把后端的 List<List<Object>> 转成 List<Record>，让 el-table 用 :prop="col.key" 能正确取数。
 * el-table 不像 el-table-v2 支持 dataKey，需要 Object 形式。
 * 列索引映射：displayColumns[i].key 对应 rawColumns[i].key（displayColumns 是 rawColumns
 * 的 visible 投影 + order 排序，索引一一对应）。
 */
function toRowsObject(arrays: any[][]): Record<string, any>[] {
  const cols = displayColumns.value;
  if (!cols.length) return [];
  return arrays.map((r, i) => {
    const obj: Record<string, any> = { __idx: i };
    cols.forEach((c, j) => { obj[c.key] = r[j]; });
    return obj;
  });
}

const pagedRows = computed(() => {
  const rows = displayRows.value;
  const start = (page.value - 1) * pageSize.value;
  return toRowsObject(rows.slice(start, start + pageSize.value));
});

const tableHeight = ref(640);


function formatCell(v: any): string {
  if (v === null || v === undefined) return '—';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

function autoWidth(name: string): number {
  return Math.min(280, Math.max(96, name.length * 9 + 28));
}

/** 跟随 InvoiceList：固定 640 max-height，不做视口适配 */
function recalcSize() { /* no-op, 跟随 InvoiceList 静态 640 */ }

async function loadQuery() {
  if (!menuId.value || isNaN(menuId.value)) {
    ElMessage.error(t('dataMgmt.qpInvalidMenuId'));
    router.replace('/data-mgmt/query');
    return;
  }
  try {
    // 先拿完整 menu（包含 configJson）
    const menu = await queryPageApi.detail(menuId.value);
    config.value = parseConfig(menu.configJson);
    // 收集 server-side 列宽（用于 column width 默认值）
    serverWidths.value = {};
    for (const [k, v] of Object.entries(config.value.columns)) {
      if (v.width) serverWidths.value[k] = v.width;
    }
    // 拿 queryId
    queryId.value = menu.queryId;
    const q = await queryApi.detail(menu.queryId);
    query.value = q;
    for (const p of q.paramsSchema) {
      paramValues.value[p.name] = p.defaultValue ?? '';
    }
    recalcSize();
    // 有必填参数无默认值时，不自动跑 —— 等用户填完触发
    const hasMissingRequired = q.paramsSchema.some(
      p => p.required && !paramValues.value[p.name]
    );
    if (!hasMissingRequired) {
      reload();
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('dataMgmt.qpLoadFailed'));
    router.replace('/data-mgmt/query');
  }
}

/** 是否还缺必填参数 */
const hasMissingRequired = computed(() => {
  if (!query.value) return false;
  return query.value.paramsSchema.some(
    p => p.required && !paramValues.value[p.name]
  );
});

/** 防抖：连续键入 / 调整只发一次请求 */
let reloadTimer: number | null = null;
function scheduleReload(delay = 300) {
  if (reloadTimer) window.clearTimeout(reloadTimer);
  reloadTimer = window.setTimeout(() => {
    reloadTimer = null;
    reload();
  }, delay);
}

/** pager 当前页变化（client-side 分页，不需重发请求） */
function onPagerChange(_p: number) { /* pagedRows computed 会自动更新 */ }

async function reload() {
  if (!queryId.value) return;
  loading.value = true;
  runError.value = '';
  page.value = 1;
  try {
    const res = await queryApi.run(queryId.value, { ...paramValues.value });
    // 应用 server-side 列覆盖：替换 label（custom headerText）
    if (config.value.columns) {
      res.columns = res.columns.map((c) => {
        const ov = config.value.columns[c.key];
        if (ov?.headerText) c.label = ov.headerText;
        return c;
      });
    }
    result.value = res;
  } catch (e: any) {
    runError.value = e?.response?.data?.message || t('dataMgmt.qpRunError');
    result.value = null;
  } finally {
    loading.value = false;
  }
}

function resetQuery() {
  if (!query.value) return;
  for (const p of query.value.paramsSchema) {
    paramValues.value[p.name] = p.defaultValue ?? '';
  }
  reload();
}

async function onExport(kind: 'csv' | 'xlsx') {
  if (!queryId.value) return;
  exporting.value = true;
  try {
    const token = localStorage.getItem('accessToken') || '';
    const qs = Object.entries(paramValues.value)
      .filter(([, v]) => v !== null && v !== undefined && v !== '')
      .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`).join('&');
    const url = `/api/datamgmt/queries/${queryId.value}/export.${kind}?${qs}`;
    const r = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
    if (!r.ok) {
      let msg = `HTTP ${r.status}`;
      try { const j = await r.json(); msg = j?.message || msg; } catch {}
      throw new Error(msg);
    }
    const cd = r.headers.get('Content-Disposition') || '';
    const m = cd.match(/filename="?([^"]+)"?/);
    const filename = m ? m[1] : `query-${queryId.value}.${kind}`;
    const blob = await r.blob();
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = filename;
    document.body.appendChild(a); a.click(); a.remove();
    URL.revokeObjectURL(a.href);
  } catch (e: any) {
    ElMessage.error(e?.message || t('dataMgmt.qpExportFailed'));
  } finally {
    exporting.value = false;
  }
}

onMounted(() => {
  loadQuery();
  window.addEventListener('resize', recalcSize);
});

onBeforeUnmount(() => {
  if (reloadTimer) {
    window.clearTimeout(reloadTimer);
    reloadTimer = null;
  }
  window.removeEventListener('resize', recalcSize);
});
</script>

<style scoped>
.page-header { display: flex; align-items: center; gap: 16px; margin-bottom: 8px; }
.header-left { display: flex; align-items: center; gap: 12px; flex: 1; }
.back-btn {
  background: none; border: 0; cursor: pointer; padding: 0;
  display: inline-flex; align-items: center; justify-content: center;
  width: 28px; height: 28px; color: #1A1A1A; border-radius: 3px;
}
.back-btn:hover { background: #F4F4EE; }
.back-arrow { font-size: 16px; line-height: 1; }
.page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; }
.page-desc { margin: 4px 0 0; font-size: 13px; }
.stats { white-space: nowrap; }
.mono { font-family: ui-monospace, monospace; }
.err { color: #B73838; }
.warn { color: #B73838; }
.required { color: #B73838; margin: 0 2px; }

.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.toolbar-right { margin-left: auto; display: inline-flex; gap: 8px; }
.field { display: inline-flex; align-items: center; gap: 6px; }
.label {
  font-size: 13px;
  color: var(--color-text-secondary);
  white-space: nowrap;
  user-select: none;
}

.cell-text {
  display: inline-block; max-width: 100%;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-family: ui-monospace, "JetBrains Mono", monospace;
}

.empty-hint { padding: 32px; text-align: center; font-size: 13px; }
.qp-pager { margin-top: 12px; justify-content: flex-end; display: flex; }
</style>