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
        :header-cell-style="{background:'var(--color-bg)', color:'var(--color-text)', fontWeight:600, fontSize:'13px'}"
        :cell-style="cellStyle"
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
                  @click="row.formats[f.code] && onPreview(row, f.code)"
                  @keydown.enter="row.formats[f.code] && onPreview(row, f.code)"
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
        <el-table-column column-key="issueDate" :label="t('invoice.colIssueDate')" prop="issueDate" :width="widthOf('issueDate', 120)" />
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
            <span class="text-secondary">{{ (row as InvoiceGroupItem).verificationDate || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column column-key="createdAt" :label="t('system.colCreatedAt')" prop="createdAt" :width="widthOf('createdAt', 120)">
          <template #default="{ row }">
            <span class="text-secondary">{{ ((row.createdAt as unknown as string) || '').slice(0, 10) }}</span>
          </template>
        </el-table-column>
        <el-table-column column-key="actions" :label="t('common.actions')" :width="widthOf('actions', 180)" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="auth.hasPerm('invoice:edit')"
              size="small"
              link
              type="primary"
              @click="openEdit(row as InvoiceGroupItem)"
            >
              {{ t('common.edit') }}
            </el-button>
            <el-popconfirm
              v-if="auth.hasPerm('invoice:delete')"
              :title="t('invoice.deleteGroupConfirm', { n: row.ids.length })"
              @confirm="remove(row as InvoiceGroupItem)"
            >
              <template #reference>
                <el-button size="small" link type="danger">{{ t('common.delete') }}</el-button>
              </template>
            </el-popconfirm>
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

    <el-dialog
      v-model="dialog"
      :title="t('invoice.editTitle')"
      width="640px"
      append-to-body
      destroy-on-close
      align-center
    >
      <el-form :model="form" label-width="140px">
        <el-form-item :label="t('invoice.editInvoiceNo')">
          <el-input v-model="form.invoiceNumber" />
        </el-form-item>
        <el-form-item :label="t('invoice.editIssueDate')">
          <el-date-picker
            v-model="form.issueDate"
            type="date"
            value-format="YYYY-MM-DD"
            style="width: 100%;"
          />
        </el-form-item>
        <el-form-item :label="t('invoice.editSeller')">
          <el-input v-model="form.sellerName" />
        </el-form-item>
        <el-form-item :label="t('invoice.editSellerTaxId')">
          <el-input v-model="form.sellerTaxId" />
        </el-form-item>
        <el-form-item :label="t('invoice.editBuyer')">
          <el-input v-model="form.buyerName" />
        </el-form-item>
        <el-form-item :label="t('invoice.editBuyerTaxId')">
          <el-input v-model="form.buyerTaxId" />
        </el-form-item>
        <el-form-item :label="t('invoice.editNet')">
          <el-input-number v-model="form.amountWithoutTax" :precision="2" :step="0.01" style="width: 100%;" />
        </el-form-item>
        <el-form-item :label="t('invoice.editTax')">
          <el-input-number v-model="form.taxAmount" :precision="2" :step="0.01" style="width: 100%;" />
        </el-form-item>
        <el-form-item :label="t('invoice.editTotal')">
          <el-input-number v-model="form.totalAmount" :precision="2" :step="0.01" style="width: 100%;" />
        </el-form-item>
        <el-form-item :label="t('invoice.editMessage')">
          <el-input v-model="form.message" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { Search, Refresh, Check, Brush } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { invoiceApi, type InvoiceGroupItem } from '@/api/invoice';
import CellText from '@/components/CellText.vue';
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

const dialog = ref(false);
const saving = ref(false);
const form = reactive<Partial<InvoiceGroupItem>>({});

const { widthOf, onResize: onColumnResize } = useColumnWidths('invoice-list');

function cellStyle() {
  return { fontSize: '13px' };
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

async function onPreview(row: InvoiceGroupItem, ext: string) {
  const fmt = row.formats[ext as keyof typeof row.formats];
  if (!fmt) return;
  let blobUrl: string | null = null;
  try {
    const r = await invoiceApi.preview(fmt.storageKey);
    blobUrl = r.blobUrl;
    const realExt = r.ext;
    // 可预览的格式：读字典 file_format 的 value 字段为「inlinable」，
    // 当前种子数据：pdf / xml 可内嵌；ofd 走下载
    const inlineCodes = fileFormats.value
      .filter((f) => f.code === 'pdf' || f.code === 'xml')
      .map((f) => f.code);
    if (inlineCodes.includes(realExt)) {
      const w = window.open(blobUrl, '_blank');
      if (!w) {
        ElMessage.warning(t('common.popupBlocked'));
        downloadBlob(blobUrl, fmt.originalFilename);
        return;
      }
    } else {
      ElMessage.info(t('invoice.previewFailOfd'));
      downloadBlob(blobUrl, fmt.originalFilename);
    }
  } catch (e) {
    ElMessage.error((e instanceof Error ? e.message : String(e)));
    if (blobUrl) URL.revokeObjectURL(blobUrl);
  }
}

function downloadBlob(blobUrl: string, filename: string) {
  const a = document.createElement('a');
  a.href = blobUrl;
  a.download = filename;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  // 给浏览器一点时间处理下载，再回收 blob
  setTimeout(() => {
    a.remove();
    URL.revokeObjectURL(blobUrl);
  }, 1000);
}

async function openEdit(row: InvoiceGroupItem) {
  // 编辑组内最新（id 最大）的一条记录
  try {
    const latest = await invoiceApi.detail(row.representativeId);
    Object.assign(form, {
      id: latest.id,
      invoiceNumber: latest.invoiceNumber,
      issueDate: latest.issueDate,
      sellerName: latest.sellerName,
      sellerTaxId: latest.sellerTaxId,
      buyerName: latest.buyerName,
      buyerTaxId: latest.buyerTaxId,
      amountWithoutTax: latest.amountWithoutTax,
      taxAmount: latest.taxAmount,
      totalAmount: latest.totalAmount,
      message: latest.message,
    });
    dialog.value = true;
  } catch (e) {
    ElMessage.error((e instanceof Error ? e.message : String(e)));
  }
}

async function save() {
  saving.value = true;
  try {
    await invoiceApi.update(form.id!, {
      invoiceNumber: form.invoiceNumber,
      issueDate: form.issueDate,
      sellerName: form.sellerName,
      sellerTaxId: form.sellerTaxId,
      buyerName: form.buyerName,
      buyerTaxId: form.buyerTaxId,
      amountWithoutTax: form.amountWithoutTax,
      taxAmount: form.taxAmount,
      totalAmount: form.totalAmount,
      message: form.message,
    });
    ElMessage.success(t('common.updated'));
    dialog.value = false;
    reload();
  } finally { saving.value = false; }
}

async function remove(row: InvoiceGroupItem) {
  for (const id of row.ids) {
    try { await invoiceApi.remove(id); }
    catch (e) {
      ElMessage.error((e instanceof Error ? e.message : String(e)));
      return;
    }
  }
  ElMessage.success(t('common.deleted'));
  reload();
}

onMounted(reload);
</script>

<style scoped>
.page-header { margin-bottom: 20px; }
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
</style>