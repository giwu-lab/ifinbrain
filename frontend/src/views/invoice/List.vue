<template>
  <div class="page-container fade-in-up">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('invoice.listTitle') }}</h1>
        <p class="page-desc muted">{{ t('invoice.listTitleDesc') }}</p>
      </div>
    </div>

    <el-card class="card-table" shadow="never">
      <div class="toolbar">
        <span class="field">
          <span class="label">{{ t('invoice.queryKeywordLabel') }}：</span>
          <el-input
            v-model="query.keyword"
            :placeholder="t('invoice.searchPlaceholder')"
            style="width: 260px;"
            clearable
            @keyup.enter="reload"
            :prefix-icon="Search"
          />
        </span>
        <span class="field">
          <span class="label">{{ t('invoice.queryVerificationNoLabel') }}：</span>
          <el-input
            v-model="query.verificationNo"
            :placeholder="t('invoice.verificationNoPlaceholder')"
            style="width: 200px;"
            clearable
            @keyup.enter="reload"
          />
        </span>
        <span class="field">
          <span class="label">{{ t('invoice.queryVerifierLabel') }}：</span>
          <el-input
            v-model="query.verifier"
            :placeholder="t('invoice.verifierPlaceholder')"
            style="width: 180px;"
            clearable
            @keyup.enter="reload"
          />
        </span>
        <span class="field">
          <span class="label">{{ t('invoice.queryVerificationDateLabel') }}：</span>
          <el-date-picker
            v-model="query.verificationDateRange"
            type="daterange"
            range-separator="→"
            start-placeholder="YYYY-MM-DD"
            end-placeholder="YYYY-MM-DD"
            value-format="YYYY-MM-DD"
            format="YYYY-MM-DD"
            :placeholder="t('invoice.verificationDateRangePlaceholder')"
            style="width: 260px;"
            @change="reload"
          />
        </span>
        <span class="toolbar-right">
          <el-button type="primary" :icon="Search" @click="reload">{{ t('common.search') }}</el-button>
          <el-button :icon="Refresh" @click="resetQuery">{{ t('common.reset') }}</el-button>
          <el-dropdown
            split-button
            type="success"
            :loading="exporting"
            @click="onExport('csv')"
            @command="onExport"
          >
            {{ t('invoice.exportCsv') }}
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="csv">{{ t('invoice.exportCsv') }}</el-dropdown-item>
                <el-dropdown-item command="xlsx">{{ t('invoice.exportXlsx') }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </span>
      </div>

      <el-table
        :data="rows"
        v-loading="loading"
        border
        stripe
        size="small"
        max-height="640"
        :column-resizable="true"
        @header-dragend="onColumnResize"
      >
        <!-- 发票号码：第一列，冻结 -->
        <el-table-column
          column-key="invoiceNumber"
          :label="t('invoice.colInvoiceNo')"
          prop="invoiceNumber"
          :width="widthOf('invoiceNumber', 180)"
          :min-width="120"
          fixed
        >
          <template #default="{ row }">
            <CellText :text="row.invoiceNumber" />
          </template>
        </el-table-column>

        <!-- 文件格式列：PDF / XML / OFD 复选框（只读显示 + 点击预览） -->
        <el-table-column
          column-key="format"
          :label="t('invoice.colFormat')"
          :width="widthOf('format', 200)"
          :min-width="160"
        >
          <template #default="{ row }">
            <span class="format-cell">
              <el-tooltip
                v-for="f in fileFormats"
                :key="f.code"
                :disabled="!row.formats[f.code]"
                :content="row.formats[f.code] ? row.formats[f.code]!.originalFilename : ''"
                placement="top"
              >
                <span
                  class="fmt-chip"
                  :class="['fmt-' + f.code, { active: !!row.formats[f.code] }]"
                  :role="row.formats[f.code] ? 'button' : undefined"
                  :tabindex="row.formats[f.code] ? 0 : -1"
                  @click="row.formats[f.code] && openInvoicePreview(row, f.code)"
                  @keydown.enter="row.formats[f.code] && openInvoicePreview(row, f.code)"
                >
                  <el-icon v-if="row.formats[f.code]" class="fmt-check"><Check /></el-icon>
                  <span class="fmt-text">{{ f.label }}</span>
                </span>
              </el-tooltip>
            </span>
          </template>
        </el-table-column>

        <el-table-column column-key="invoiceType" :label="t('invoice.colType')" prop="invoiceType" :width="widthOf('invoiceType', 180)">
          <template #default="{ row }">
            <el-tag effect="plain" size="small">{{ row.invoiceType || '—' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column column-key="issueDate" :label="t('invoice.colIssueDate')" prop="issueDate" :width="widthOf('issueDate', 120)">
          <template #default="{ row }">
            <span class="mono-time">{{ row.issueDate || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column column-key="sellerName" :label="t('invoice.colSeller')" prop="sellerName" :width="widthOf('sellerName', 180)" :min-width="120">
          <template #default="{ row }">
            <CellText v-if="row.sellerName" :text="row.sellerName" />
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column column-key="buyerName" :label="t('invoice.colBuyer')" prop="buyerName" :width="widthOf('buyerName', 160)" :min-width="120">
          <template #default="{ row }">
            <CellText v-if="row.buyerName" :text="row.buyerName" />
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column column-key="amountWithoutTax" :label="t('invoice.colNet')" prop="amountWithoutTax" :width="widthOf('amountWithoutTax', 120)" align="right">
          <template #default="{ row }">
            <span v-if="row.amountWithoutTax">{{ row.amountWithoutTax.toFixed(2) }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column column-key="taxAmount" :label="t('invoice.colTax')" prop="taxAmount" :width="widthOf('taxAmount', 100)" align="right">
          <template #default="{ row }">
            <span v-if="row.taxAmount">{{ row.taxAmount.toFixed(2) }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column column-key="totalAmount" :label="t('invoice.colTotal')" prop="totalAmount" :width="widthOf('totalAmount', 120)" align="right">
          <template #default="{ row }">
            <strong v-if="row.totalAmount">{{ row.totalAmount.toFixed(2) }}</strong>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column column-key="message" :label="t('invoice.colMessage')" :width="widthOf('message', 160)" :min-width="120">
          <template #default="{ row }">
            <el-tooltip
              placement="top"
              :disabled="!((row as InvoiceGroupItem).message && (row as InvoiceGroupItem).message!.length > 20)"
              :content="(row as InvoiceGroupItem).message!"
            >
              <el-tag
                :type="statusTagType((row as InvoiceGroupItem).message)"
                effect="dark"
                size="small"
                class="msg-tag"
              >
                {{ row.message }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column column-key="verificationNo" :label="t('invoice.colVerificationNo')" :width="widthOf('verificationNo', 160)" :min-width="120">
          <template #default="{ row }">
            <CellText v-if="(row as InvoiceGroupItem).verificationNo" :text="(row as InvoiceGroupItem).verificationNo" />
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column column-key="verifier" :label="t('invoice.colVerifier')" :width="widthOf('verifier', 120)" :min-width="80">
          <template #default="{ row }">
            <CellText v-if="(row as InvoiceGroupItem).verifier" :text="(row as InvoiceGroupItem).verifier" />
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column column-key="verificationDate" :label="t('invoice.colVerificationDate')" prop="verificationDate" :width="widthOf('verificationDate', 120)">
          <template #default="{ row }">
            <span class="mono-time">{{ (row as InvoiceGroupItem).verificationDate || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column column-key="createdAt" :label="t('system.colCreatedAt')" prop="createdAt" :width="widthOf('createdAt', 120)">
          <template #default="{ row }">
            <span class="mono-time">{{ ((row.createdAt as unknown as string) || '').slice(0, 10) }}</span>
          </template>
        </el-table-column>
        <el-table-column column-key="actions" :label="t('common.actions')" :width="widthOf('actions', 80)" align="center" fixed="right">
          <template #default="{ row }">
            <!-- 删除：保留在 ··· 下拉（admin-only，invoice:delete） -->
            <el-dropdown v-if="auth.hasPerm('invoice:delete')" trigger="click" @command="(cmd: string) => onRowCmd(cmd, row as InvoiceGroupItem)">
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

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10,20,50,100]"
        layout="total,sizes,prev,pager,next,jumper"
        @current-change="reload"
        @size-change="reload"
      />
    </el-card>

    <!-- 发票文件预览抽屉（参考 documents/list/Index.vue 档案库预览）：
         drawer 80% rtl + #header slot 自定义（Document 图标 + 文件名 + 下载 + 关闭）
         + <ProjectFileViewer> 内嵌渲染 PDF / XML / OFD。
         :destroy-on-close 保证旧 ArrayBuffer 不堆积；
         class="invoice-preview-drawer" 用更高特异性恢复 StylePanel.vue:207 的全局隐藏 -->
    <el-drawer
      v-model="previewVisible"
      class="invoice-preview-drawer"
      size="80%"
      direction="rtl"
      :destroy-on-close="true"
      :show-close="false"
    >
      <template #header>
        <div class="preview-drawer-header">
          <div class="preview-drawer-title" :title="previewTitle">
            <el-icon class="preview-title-icon"><Document /></el-icon>
            <span class="preview-title-text">{{ previewTitle }}</span>
          </div>
          <div class="preview-drawer-actions">
            <el-tooltip :content="t('common.download')" placement="bottom">
              <el-button
                :icon="Download"
                circle
                plain
                size="default"
                @click="onInvoicePreviewDownload({ storageKey: previewKey, name: previewTitle })"
              />
            </el-tooltip>
            <el-tooltip :content="t('common.close')" placement="bottom">
              <el-button
                :icon="Close"
                circle
                plain
                size="default"
                @click="previewVisible = false"
              />
            </el-tooltip>
          </div>
        </div>
      </template>
      <ProjectFileViewer
        v-if="previewKey"
        :storage-key="previewKey"
        :filename="previewTitle"
        @download="onInvoicePreviewDownload"
      />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Search, Refresh, Check, Delete, MoreFilled, Document, Download, Close } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { invoiceApi, type InvoiceGroupItem } from '@/api/invoice';
import CellText from '@/components/CellText.vue';
import ProjectFileViewer from '@/components/ProjectFileViewer.vue';
import { useColumnWidths } from '@/composables/useColumnWidths';
import { useFileFormat, useInvoiceStatus, useDictStore, DICT_INVOICE_STATUS } from '@/stores/dict';

const { t } = useI18n();
const auth = useAuthStore();

/** 字典：文件格式 + 发票状态 */
const { items: fileFormats } = useFileFormat();
const dictStore = useDictStore();

/** 根据 invoice message 字符串算出 el-tag type（由字典驱动） */
function statusTagType(msg: string | undefined): 'success' | 'danger' | 'warning' {
  if (!msg) return 'warning';
  const successItem = dictStore.getByValue(DICT_INVOICE_STATUS, 'success');
  if (successItem && msg === successItem.value) return 'success';
  const errorItem = dictStore.getByValue(DICT_INVOICE_STATUS, 'error');
  if (errorItem && (msg === errorItem.value || msg.startsWith(errorItem.value))) return 'danger';
  return 'warning';
}
const rows = ref<InvoiceGroupItem[]>([]);
const total = ref(0);
const loading = ref(false);
const exporting = ref(false);
const query = reactive({
  page: 1,
  size: 10,
  keyword: '',
  verificationNo: '',
  verifier: '',
  verificationDateRange: [] as string[],
});

const { widthOf, onResize: onColumnResize } = useColumnWidths('invoice-list');

/** 文件预览抽屉（参考 documents/list/Index.vue 档案库预览实现） */
const previewVisible = ref(false);
const previewKey = ref('');
const previewTitle = ref('');

/** 打开指定格式的发票预览抽屉（替换原 onPreview 新标签页逻辑） */
function openInvoicePreview(row: InvoiceGroupItem, ext: string) {
  const fmt = row.formats[ext as keyof typeof row.formats];
  if (!fmt) return;
  previewKey.value = fmt.storageKey;
  previewTitle.value = fmt.originalFilename;
  previewVisible.value = true;
}

/** ProjectFileViewer 内 toolbar 下载按钮 → fetch blob（不走 axios）+ JWT refresh retry */
async function onInvoicePreviewDownload(payload: { storageKey: string; name: string }) {
  try {
    let blob = await fetchInvoiceBlob(payload.storageKey);
    if (!blob) {
      await auth.refreshTokens();
      blob = await fetchInvoiceBlob(payload.storageKey);
    }
    if (!blob) throw new Error('refresh+retry failed');
    saveInvoiceBlob(blob, payload.name);
  } catch (err: unknown) {
    ElMessage.error((err instanceof Error ? err.message : String(err)) || t('invoice.exportFail'));
  }
}

async function fetchInvoiceBlob(key: string): Promise<Blob | null> {
  const resp = await fetch(`/api/files/raw?key=${encodeURIComponent(key)}&download=true`, {
    headers: { Authorization: `Bearer ${localStorage.getItem('accessToken') || ''}` },
  });
  if (resp.ok) return resp.blob();
  if (resp.status === 403 || resp.status === 401) return null;
  const body = await resp.json().catch(() => ({}));
  throw new Error(body?.message || `HTTP ${resp.status}`);
}

function saveInvoiceBlob(blob: Blob, filename: string) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(a.href);
}

async function reload() {
  loading.value = true;
  try {
    const [from, to] = splitDateRange(query.verificationDateRange);
    const r = await invoiceApi.listGrouped({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      verificationNo: query.verificationNo || undefined,
      verifier: query.verifier || undefined,
      verificationDateFrom: from,
      verificationDateTo: to,
    });
    rows.value = r.items;
    total.value = r.total;
  } finally { loading.value = false; }
}

function splitDateRange(v: string[] | undefined | null): [string | undefined, string | undefined] {
  if (!v || !Array.isArray(v) || v.length === 0) return [undefined, undefined];
  const from = v[0] || undefined;
  const to = v[1] || undefined;
  return [from, to];
}

function resetQuery() {
  query.keyword = '';
  query.verificationNo = '';
  query.verifier = '';
  query.verificationDateRange = [];
  query.page = 1;
  reload();
}

async function onExport(kind: 'csv' | 'xlsx') {
  if (exporting.value) return;
  exporting.value = true;
  try {
    const [from, to] = splitDateRange(query.verificationDateRange);
    await invoiceApi.exportFile(kind, {
      keyword: query.keyword || undefined,
      verificationNo: query.verificationNo || undefined,
      verifier: query.verifier || undefined,
      verificationDateFrom: from,
      verificationDateTo: to,
    });
    ElMessage.success(t('invoice.exportOk', { kind: kind.toUpperCase() }));
  } catch (e: unknown) {
    ElMessage.error(t('invoice.exportFail') + ': ' + (e instanceof Error ? e.message : String(e)));
  } finally {
    exporting.value = false;
  }
}

async function remove(row: InvoiceGroupItem) {
  try {
    const r = await invoiceApi.batchDelete(row.ids);
    if (r.filesMissing > 0) {
      // 文件没全删干净 —— 不阻塞 reload，但要让 admin 知情（去 logs 找 missing[].reason）
      ElMessage.warning(t('invoice.deletedWithMissing', { n: r.filesMissing }));
    } else {
      ElMessage.success(t('common.deleted'));
    }
  } catch (e) {
    ElMessage.error((e instanceof Error ? e.message : String(e)));
    return;
  }
  reload();
}

/** ··· 下拉菜单统一入口 */
async function onRowCmd(cmd: string, row: InvoiceGroupItem) {
  if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(
        t('invoice.deleteGroupConfirm', { n: row.ids.length }),
        t('common.confirmDelete'),
        { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') }
      );
      await remove(row);
    } catch { /* 用户取消 */ }
  }
}

onMounted(reload);
</script>

<style scoped>
.page-header { margin-bottom: 10px; }
.page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; }
.page-desc { margin: 4px 0 0; font-size: 13px; }
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.toolbar-right { margin-left: auto; display: inline-flex; gap: 8px; }
.field { display: inline-flex; align-items: center; gap: 6px; }
.label {
  font-size: 13px;
  color: var(--color-text-secondary);
  white-space: nowrap;
  user-select: none;
}
.msg-tag { max-width: 100%; }
.msg-tag :deep(.el-tag__content) {
  display: inline-block; max-width: 100%;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

/* 文件格式复选框样式 */
.format-cell { display: inline-flex; gap: 6px; align-items: center; }
.fmt-chip {
  display: inline-flex; align-items: center; gap: 3px;
  padding: 1px 8px;
  border: 1px solid var(--color-border, #d1d5db);
  border-radius: 10px;
  font-size: 12px;
  line-height: 18px;
  background: #f9fafb;
  color: var(--color-text-secondary, #6b7280);
  cursor: not-allowed;
  user-select: none;
  transition: all .15s ease;
}
.fmt-chip.active {
  background: #ecfdf5;
  border-color: #34d399;
  color: #047857;
  cursor: pointer;
}
.fmt-chip.active:hover {
  background: #d1fae5;
  border-color: #10b981;
}
.fmt-chip .fmt-check {
  font-size: 12px;
  color: #10b981;
}
.fmt-chip .fmt-text { font-weight: 600; letter-spacing: 0.02em; }

/* 预览抽屉自定义 header（参考 documents/list/Index.vue 档案库预览） */
.preview-drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-width: 0;
  flex: 1 1 auto;
}
.preview-drawer-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
  color: var(--color-text);
}
.preview-title-icon {
  flex-shrink: 0;
  font-size: 16px;
  color: var(--color-primary);
}
.preview-title-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 15px;
  font-weight: 600;
}
.preview-drawer-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
</style>

<style>
/*
 * 关键修复：StylePanel.vue:207 的全局规则
 *   .el-drawer__header { display: none; }
 * 把所有 el-drawer 的 <header> 都隐藏了 —— 包括我们的预览抽屉。
 * 用更高的特异性（0,2,0 vs 0,1,0）只对本抽屉恢复显示。
 * 非 scoped 块：el-drawer 经 <Teleport> 到 body，scoped :deep() 匹配不到。
 */
.invoice-preview-drawer .el-drawer__header {
  display: flex;
}
</style>