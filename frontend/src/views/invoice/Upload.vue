<template>
  <div class="page-container fade-in-up">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('invoice.uploadTitle') }}</h1>
        <p class="page-desc muted">{{ t('invoice.uploadDesc') }}</p>
      </div>
    </div>

    <el-card class="card-table upload-card" shadow="never">
      <el-form :model="meta" label-width="120px" class="meta-form">
        <el-row :gutter="20">
          <el-col :md="12" :lg="8">
            <el-form-item :label="t('invoice.verificationNoLabel')" required>
              <el-input
                v-model="meta.verificationNo"
                :placeholder="t('invoice.verificationNoPlaceholder')"
                clearable
              />
            </el-form-item>
          </el-col>
          <el-col :md="12" :lg="8">
            <el-form-item :label="t('invoice.verifierLabel')">
              <el-input
                v-model="meta.verifier"
                :placeholder="t('invoice.verifierPlaceholder')"
                clearable
              />
            </el-form-item>
          </el-col>
          <el-col :md="12" :lg="8">
            <el-form-item :label="t('invoice.verificationDateLabel')">
              <el-date-picker
                v-model="meta.verificationDate"
                type="date"
                value-format="YYYY-MM-DD"
                format="YYYY-MM-DD"
                style="width: 100%;"
              />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <el-divider />

      <el-upload
        ref="uploaderRef"
        drag
        multiple
        :auto-upload="false"
        :limit="50"
        :on-exceed="onExceed"
        :on-change="onChange"
        :on-remove="onRemove"
        :file-list="files"
        :accept="acceptAttr"
        class="upload-area"
      >
        <el-icon class="upload-icon"><UploadFilled /></el-icon>
        <div class="upload-title">{{ t('invoice.dropHere') }}</div>
        <div class="upload-hint">{{ t('invoice.uploadDesc') }}</div>
      </el-upload>

      <div class="action-row">
        <el-button
          type="primary"
          size="large"
          :disabled="!files.length || loading || !meta.verificationNo.trim()"
          :loading="loading"
          :icon="UploadFilled"
          @click="submit"
        >
          {{ t('invoice.submit', { count: files.length }) }}
        </el-button>
        <el-button
          size="large"
          @click="files = []; results = []"
          :disabled="loading || (!files.length && !results.length)"
        >
          {{ t('invoice.clear') }}
        </el-button>
        <div class="file-count" v-if="files.length">
          <el-icon><Document /></el-icon>
          {{ files.length }} {{ t('common.actions') }}
        </div>
      </div>
    </el-card>

    <!-- 识别成功后 -->
    <el-card
      v-if="successRows.length"
      class="card-table result-card"
      shadow="never"
    >
      <template #header>
        <div class="result-header">
          <span class="card-title">
            <el-icon style="color:#10b981;"><CircleCheckFilled /></el-icon>
            {{ t('invoice.resultsOkTitle') }}
          </span>
          <el-tag type="success" effect="light">
            {{ t('invoice.recordsCount', { count: successRows.length }) }}
          </el-tag>
        </div>
      </template>
      <el-table
        :data="successRows"
        border
        stripe
        size="small"
        max-height="640"
        :header-cell-style="{background:'var(--color-bg)', color:'var(--color-text)', fontWeight:600, fontSize:'13px'}"
        :cell-style="cellStyle"
      >
        <el-table-column :label="t('invoice.colFile')" width="280" fixed>
          <template #default="{ row }">
            <div class="file-cell">
              <el-icon class="file-icon"><Document /></el-icon>
              <CellText :text="row.originalFilename" />
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colInvoiceNo')" width="240" fixed>
          <template #default="{ row }">
            <el-input v-model="row.invoiceNumber" size="small" placeholder="—" />
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colType')" min-width="140">
          <template #default="{ row }">
            <el-input v-model="row.invoiceType" size="small" placeholder="—" />
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colPageCount')" width="80" align="right">
          <template #default="{ row }">
            <span v-if="row.pageCount != null">{{ row.pageCount }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colIssueDate')" width="130">
          <template #default="{ row }">
            <span class="text-secondary">{{ row.issueDate || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colSeller')" min-width="200">
          <template #default="{ row }">
            <el-input v-model="row.sellerName" size="small" placeholder="—" />
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colSellerTaxId')" min-width="180">
          <template #default="{ row }">
            <el-input v-model="row.sellerTaxId" size="small" placeholder="—" />
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colBuyer')" min-width="200">
          <template #default="{ row }">
            <el-input v-model="row.buyerName" size="small" placeholder="—" />
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colBuyerTaxId')" min-width="180">
          <template #default="{ row }">
            <el-input v-model="row.buyerTaxId" size="small" placeholder="—" />
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colNet')" width="120" align="right">
          <template #default="{ row }">
            <span v-if="row.amountWithoutTax">{{ row.amountWithoutTax.toFixed(2) }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colTax')" width="100" align="right">
          <template #default="{ row }">
            <span v-if="row.taxAmount">{{ row.taxAmount.toFixed(2) }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colTotal')" width="120" align="right">
          <template #default="{ row }">
            <strong v-if="row.totalAmount">{{ row.totalAmount.toFixed(2) }}</strong>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colTotalUpper')" min-width="160">
          <template #default="{ row }">
            <CellText :text="row.totalAmountUpper" />
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colPayee')" width="110">
          <template #default="{ row }">
            <el-input v-model="row.payee" size="small" placeholder="—" />
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colReviewer')" width="110">
          <template #default="{ row }">
            <el-input v-model="row.reviewer" size="small" placeholder="—" />
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colDrawer')" width="110">
          <template #default="{ row }">
            <el-input v-model="row.drawer" size="small" placeholder="—" />
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 重复发票 -->
    <el-card
      v-if="duplicateRows.length"
      class="card-table result-card"
      shadow="never"
    >
      <template #header>
        <div class="result-header">
          <span class="card-title">
            <el-icon style="color:#f59e0b;"><WarningFilled /></el-icon>
            {{ t('invoice.resultsDupTitle') }}
          </span>
          <el-tag type="warning" effect="light">
            {{ t('invoice.recordsCount', { count: duplicateRows.length }) }}
          </el-tag>
        </div>
      </template>

      <el-alert
        v-for="g in duplicateGroups"
        :key="g.key"
        type="warning"
        :closable="false"
        show-icon
        class="dup-alert"
      >
        <template #title>
          {{ t('invoice.dupGroupTitle', {
            no: g.invoiceNumber,
            ext: g.ext || '—',
            count: g.rows.length,
          }) }}
        </template>
      </el-alert>

      <el-table
        :data="duplicateRows"
        border
        stripe
        size="small"
        max-height="480"
        :row-class-name="dupRowClass"
        :header-cell-style="{background:'var(--color-bg)', color:'var(--color-text)', fontWeight:600, fontSize:'13px'}"
        :cell-style="cellStyle"
      >
        <el-table-column :label="t('invoice.colInvoiceNo')" min-width="160">
          <template #default="{ row }">
            <el-input v-model="row.invoiceNumber" size="small" placeholder="—" />
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colFile')" min-width="180">
          <template #default="{ row }">
            <div class="file-cell">
              <el-icon class="file-icon" style="color:#f59e0b;"><WarningFilled /></el-icon>
              <CellText :text="row.originalFilename" />
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colFileExt')" min-width="100">
          <template #default="{ row }">
            <el-tag effect="plain" size="small">{{ extOf(row.originalFilename) || '—' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colSeller')" min-width="180">
          <template #default="{ row }">
            <CellText :text="row.sellerName" />
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colTotal')" width="120" align="right">
          <template #default="{ row }">
            <strong v-if="row.totalAmount">{{ row.totalAmount.toFixed(2) }}</strong>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colVerificationNo')" min-width="140">
          <template #default="{ row }">
            <span class="text-secondary">{{ meta.verificationNo || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colVerifier')" min-width="100">
          <template #default>
            <span class="text-secondary">{{ meta.verifier || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colVerificationDate')" width="130">
          <template #default>
            <span class="text-secondary">{{ meta.verificationDate || '—' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 数据库已有重复：全库重复组 + 本批次发票号中已存在的记录 -->
    <el-card
      v-if="results.length"
      class="card-table result-card"
      shadow="never"
    >
      <template #header>
        <div class="result-header">
          <span class="card-title">
            <el-icon :style="dbDupAllCount ? 'color:#ef4444;' : 'color:#10b981;'">
              <component :is="dbDupAllCount ? CircleCloseFilled : CircleCheckFilled" />
            </el-icon>
            {{ t('invoice.dbDupTitle') }}
          </span>
          <div class="header-tags">
            <el-tag :type="dbDupAllCount ? 'danger' : 'success'" effect="dark">
              {{ t('invoice.dbDupHits', { count: dbDupAllCount }) }}
            </el-tag>
            <el-tag :type="dbDupAllCount ? 'danger' : 'success'" effect="light">
              {{ t('invoice.dbDupInvoiceNumbers', { count: dbDupInvoiceNumberCount }) }}
            </el-tag>
          </div>
        </div>
      </template>
      <p class="dup-hint">{{ dbDupAllCount ? t('invoice.dbDupHintAll') : t('invoice.dbDupHintClean') }}</p>
      <el-table
        v-if="dbDupAllCount"
        :data="dbDupAllRecords"
        border
        stripe
        size="small"
        max-height="480"
        :header-cell-style="{background:'var(--color-bg)', color:'var(--color-text)', fontWeight:600, fontSize:'13px'}"
        :cell-style="cellStyle"
      >
        <el-table-column :label="t('invoice.colFile')" width="280" fixed>
          <template #default="{ row }">
            <div class="file-cell">
              <el-icon class="file-icon" style="color:#ef4444;"><WarningFilled /></el-icon>
              <CellText :text="row.originalFilename" />
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colInvoiceNo')" width="240" fixed>
          <template #default="{ row }">
            <CellText :text="row.invoiceNumber" />
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colFileExt')" min-width="90">
          <template #default="{ row }">
            <el-tag effect="plain" size="small">{{ extOf(row.originalFilename) || '—' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colSeller')" min-width="200">
          <template #default="{ row }">
            <CellText :text="row.sellerName" />
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colBuyer')" min-width="200">
          <template #default="{ row }">
            <CellText v-if="row.buyerName" :text="row.buyerName" />
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colTotal')" width="120" align="right">
          <template #default="{ row }">
            <strong v-if="row.totalAmount">{{ row.totalAmount.toFixed(2) }}</strong>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colVerificationNo')" min-width="140">
          <template #default="{ row }">
            <span class="text-secondary">{{ row.verificationNo || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colVerifier')" min-width="100">
          <template #default="{ row }">
            <span class="text-secondary">{{ row.verifier || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colVerificationDate')" width="130">
          <template #default="{ row }">
            <span class="text-secondary">{{ row.verificationDate || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colCreatedDate')" min-width="120">
          <template #default="{ row }">
            <span class="text-secondary">{{ (row.createdAt || '').slice(0, 10) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 识别异常 -->
    <el-card
      v-if="errorRows.length"
      class="card-table result-card"
      shadow="never"
    >
      <template #header>
        <div class="result-header">
          <span class="card-title">
            <el-icon style="color:#ef4444;"><CircleCloseFilled /></el-icon>
            {{ t('invoice.resultsErrTitle') }}
          </span>
          <el-tag type="danger" effect="light">
            {{ t('invoice.recordsCount', { count: errorRows.length }) }}
          </el-tag>
        </div>
      </template>
      <el-table
        :data="errorRows"
        border
        stripe
        size="small"
        max-height="480"
        :header-cell-style="{background:'var(--color-bg)', color:'var(--color-text)', fontWeight:600, fontSize:'13px'}"
        :cell-style="cellStyle"
      >
        <el-table-column :label="t('invoice.colFile')" min-width="180">
          <template #default="{ row }">
            <div class="file-cell">
              <el-icon class="file-icon" style="color:#ef4444;"><WarningFilled /></el-icon>
              <CellText :text="row.originalFilename" />
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colInvoiceNo')" min-width="160">
          <template #default="{ row }">
            <span class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('invoice.colMessage')" min-width="240">
          <template #default="{ row }">
            <el-tooltip
              placement="top"
              :disabled="!row.message"
              :content="row.message || ''"
            >
              <el-tag type="danger" effect="dark" size="small" class="msg-tag">{{ row.message }}</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 保存按钮：识别结果存在即可点；只提交成功行 -->
    <div v-if="results.length" class="save-bar">
      <div v-if="hasBlocking" class="block-pane">
        <el-alert
          v-if="blocking.err"
          type="error"
          :closable="false"
          show-icon
          class="block-alert"
        >
          <template #title>
            {{ t('invoice.blockErrTitle') }}（{{ errorRows.length }}）
          </template>
          <template #default>
            {{ t('invoice.blockErrDesc') }}
          </template>
        </el-alert>
        <el-alert
          v-if="blocking.dbDup"
          type="error"
          :closable="false"
          show-icon
          class="block-alert"
        >
          <template #title>
            {{ t('invoice.blockDbDupTitle') }}（{{ dbDupAllCount }} 条 / 涉及 {{ dbDupInvoiceNumberCount }} 个发票号）
          </template>
          <template #default>
            {{ t('invoice.blockDbDupDesc') }}
          </template>
        </el-alert>
        <el-alert
          v-if="blocking.dup"
          type="error"
          :closable="false"
          show-icon
          class="block-alert"
        >
          <template #title>
            {{ t('invoice.blockDupTitle') }}（{{ duplicateRows.length }}）
          </template>
          <template #default>
            {{ t('invoice.blockDupDesc') }}
          </template>
        </el-alert>
      </div>
      <el-button
        type="success"
        size="large"
        :loading="saving"
        :icon="Folder"
        :disabled="hasBlocking || !successRows.length"
        @click="saveCommit"
      >
        {{ t('invoice.saveBtn', { count: successRows.length }) }}
      </el-button>
      <span class="save-hint">{{ t('invoice.saveHint') }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRouter } from 'vue-router';
import {
  UploadFilled, Document, Folder,
  CircleCheckFilled, CircleCloseFilled, WarningFilled,
} from '@element-plus/icons-vue';
import {
  ElMessage, ElMessageBox,
  type UploadFile, type UploadRawFile, type UploadInstance,
} from 'element-plus';
import {
  invoiceApi, type PendingInvoice, type InvoiceItem, type CommitBody,
} from '@/api/invoice';
import CellText from '@/components/CellText.vue';
import { useFileFormat, useInvoiceStatus } from '@/stores/dict';

const { t } = useI18n();
const router = useRouter();

const { acceptAttr } = useFileFormat();
const { isSuccess } = useInvoiceStatus();

const uploaderRef = ref<UploadInstance>();
const files = ref<UploadFile[]>([]);
const results = ref<PendingInvoice[]>([]);
const loading = ref(false);
const saving = ref(false);

const meta = reactive({
  verificationNo: '',
  verifier: '',
  verificationDate: todayStr(),
});

function todayStr() {
  const d = new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

const successRows = computed(() =>
  results.value.filter((r) => isSuccess(r.message))
);
const errorRows = computed(() =>
  results.value.filter((r) => !isSuccess(r.message))
);

/** 重复发票：同 (发票号码, 文件扩展名) 在本次识别结果里出现 ≥ 2 条 */
const duplicateGroups = computed(() => {
  const groups = new Map<string, PendingInvoice[]>();
  for (const r of successRows.value) {
    const no = (r.invoiceNumber || '').trim();
    if (!no) continue;
    const dot = (r.originalFilename || '').lastIndexOf('.');
    const ext = dot > 0 ? (r.originalFilename || '').slice(dot + 1).toLowerCase() : '';
    const key = `${no}::${ext}`;
    const arr = groups.get(key) ?? [];
    arr.push(r);
    groups.set(key, arr);
  }
  return Array.from(groups.entries())
    .filter(([, arr]) => arr.length > 1)
    .map(([key, rows]) => {
      const [invoiceNumber, ext] = key.split('::');
      return { key, invoiceNumber, ext, rows };
    });
});
const duplicateRows = computed(() =>
  duplicateGroups.value.flatMap((g) => g.rows)
);

/** 全库查重：识别成功后从 db 拉（按当前核销单号过滤） */
const dbDupGroups = ref<{
  key: string; invoiceNumber: string; fileExt: string;
  count: number; records: InvoiceItem[];
}[]>([]);
/** 本批次发票号中已存在于 db 的单条记录（count 可为 1） */
const existingInDb = ref<InvoiceItem[]>([]);
const dbDupLoading = ref(false);

async function loadDbDuplicates() {
  if (!meta.verificationNo.trim()) {
    dbDupGroups.value = [];
    existingInDb.value = [];
    return;
  }
  dbDupLoading.value = true;
  try {
    dbDupGroups.value = await invoiceApi.duplicates({
      verificationNo: meta.verificationNo.trim() || undefined,
      verifier: meta.verifier.trim() || undefined,
      verificationDate: meta.verificationDate || undefined,
    });
  } catch (e: unknown) {
    console.warn('load duplicates failed', e);
    dbDupGroups.value = [];
  }
  // 拉本批次发票号中已存在的单条记录
  try {
    const nums = successRows.value
      .map((r) => (r.invoiceNumber || '').trim())
      .filter((n) => n.length > 0);
    existingInDb.value = nums.length ? await invoiceApi.exists(nums) : [];
  } catch (e) {
    console.warn('load exists failed', e);
    existingInDb.value = [];
  } finally {
    dbDupLoading.value = false;
  }
}

/** 全库重复总记录数 = 各组 count 求和 */
const dbDupTotalRecords = computed(() =>
  dbDupGroups.value.reduce((s, g) => s + g.count, 0)
);

/** 已存在记录中，剔除已在 dbDupGroups 中显示的，避免重复展示 */
const existingOnlyInBatch = computed(() => {
  const dupKeys = new Set(dbDupGroups.value.map((g) => g.key));
  return existingInDb.value.filter((r) => {
    const ext = extOf(r.originalFilename);
    return !dupKeys.has(`${r.invoiceNumber}::${ext}`);
  });
});
/** 「数据库已有重复」卡片中的全部记录：合并全库重复组 + 本批次独有的已存在单条 */
const dbDupAllRecords = computed(() => [
  ...dbDupGroups.value.flatMap((g) => g.records),
  ...existingOnlyInBatch.value,
]);
const dbDupAllCount = computed(() =>
  dbDupGroups.value.reduce((s, g) => s + g.count, 0) + existingOnlyInBatch.value.length
);
/** 涉及的不同发票号数 */
const dbDupInvoiceNumberCount = computed(() => {
  const s = new Set<string>();
  for (const r of dbDupAllRecords.value) {
    const no = (r.invoiceNumber || '').trim();
    if (no) s.add(no);
  }
  return s.size;
});

/**
 * 是否存在阻碍保存的问题：识别失败 / 本批次重复。
 * 数据库已有重复仅作为参考展示，不阻断保存（不属插入时检测）。
 */
const blocking = computed(() => ({
  dup: duplicateRows.value.length > 0,
  dbDup: dbDupAllCount.value > 0,
  err: errorRows.value.length > 0,
}));
const hasBlocking = computed(() => blocking.value.dup || blocking.value.dbDup || blocking.value.err);

function cellStyle() { return { fontSize: '13px' }; }

function extOf(name: string | undefined | null): string {
  if (!name) return '';
  const i = name.lastIndexOf('.');
  return i > 0 ? name.slice(i + 1).toLowerCase() : '';
}

function dupRowClass({ row }: { row: PendingInvoice }) {
  return extOf(row.originalFilename) && row.invoiceNumber ? 'dup-row' : '';
}

function onChange(_file: UploadFile, list: UploadFile[]) { files.value = list; }
function onRemove(_file: UploadFile, list: UploadFile[]) { files.value = list; }
function onExceed() { ElMessage.warning(t('invoice.uploadError')); }

async function submit() {
  const raw = files.value.map((f) => f.raw).filter(Boolean) as UploadRawFile[];
  if (!raw.length) return;
  if (!meta.verificationNo.trim()) {
    ElMessage.warning(t('invoice.verificationNoRequired'));
    return;
  }
  loading.value = true;
  try {
    const resp = await invoiceApi.uploadAndRecognize(raw as File[]);
    results.value = resp;
    const needConfig = resp.some((r) => r.message === 'OCR_NOT_CONFIGURED');
    if (needConfig) {
      ElMessageBox.alert(t('invoice.ocrNotConfiguredMsg'), t('invoice.ocrNotConfiguredTitle'), {
        type: 'warning',
        confirmButtonText: t('invoice.ocrNotConfiguredGoto'),
        cancelButtonText: t('common.cancel'),
        showCancelButton: true,
      })
        .then(() => router.push('/system/customurl'))
        .catch(() => { /* dismissed */ });
    }
    // 识别成功后查 db 已有重复（按当前核销单号过滤）
    loadDbDuplicates();
  } finally {
    loading.value = false;
  }
}

async function saveCommit() {
  if (!successRows.value.length) {
    ElMessage.warning(t('invoice.saveNoSuccess'));
    return;
  }
  if (!meta.verificationNo.trim()) {
    ElMessage.warning(t('invoice.verificationNoRequired'));
    return;
  }
  if (hasBlocking.value) {
    ElMessage.error(t('invoice.saveBlocked'));
    return;
  }
  saving.value = true;
  try {
    const body: CommitBody = {
      items: successRows.value,
      verificationNo: meta.verificationNo.trim(),
      verifier: meta.verifier.trim() || undefined,
      verificationDate: meta.verificationDate || undefined,
    };
    const saved = await invoiceApi.commit(body);
    ElMessage.success(t('invoice.saveOk', { count: saved.length }));
    // 清理：清掉已保存的文件 + 结果；保留已识别失败的项目供用户查阅
    files.value = [];
    results.value = results.value.filter((r) => !isSuccess(r.message));
  } finally {
    saving.value = false;
  }
}
</script>

<style scoped>
.page-header { margin-bottom: 20px; }
.page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; }
.page-desc { margin: 4px 0 0; font-size: 13px; }

.upload-card { padding: 0; }
.meta-form { padding: 4px 0 0; }
:deep(.upload-area) { width: 100%; }
:deep(.upload-area .el-upload-dragger) {
  background: linear-gradient(135deg, #f0f4ff 0%, #f5f3ff 100%);
  border: 2px dashed #c7d2fe;
  border-radius: 14px;
  padding: 24px 24px;
  transition: all 0.2s ease;
}
:deep(.upload-area .el-upload-dragger:hover) {
  border-color: var(--color-primary);
  background: linear-gradient(135deg, #eff6ff 0%, #eef2ff 100%);
}
.upload-icon { font-size: 40px; color: var(--color-primary); margin-bottom: 6px; }
.upload-title { font-size: 14px; font-weight: 600; color: var(--color-text); margin-bottom: 4px; }
.upload-hint { font-size: 12px; color: var(--color-text-secondary); }

.action-row { margin-top: 20px; display: flex; align-items: center; gap: 12px; }
.file-count {
  margin-left: auto;
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 13px; color: var(--color-text-secondary);
}

.result-card { margin-top: 20px; }
.result-header { display: flex; align-items: center; gap: 12px; }
.card-title { font-weight: 600; display: inline-flex; align-items: center; gap: 6px; }
.file-cell { display: flex; align-items: center; gap: 6px; min-width: 0; }
.file-cell .truncate { flex: 1; min-width: 0; }
.file-icon { color: var(--color-primary); font-size: 16px; flex-shrink: 0; }
.msg-tag { max-width: 100%; }
.msg-tag :deep(.el-tag__content) {
  display: inline-block; max-width: 100%;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

.save-bar {
  margin-top: 20px;
  display: flex; flex-wrap: wrap; align-items: center; gap: 12px;
  padding: 16px 20px;
  background: white;
  border-radius: 10px;
  border: 1px solid var(--color-border-soft);
  position: sticky;
  bottom: 20px;
  z-index: 5;
  box-shadow: 0 6px 16px rgba(15, 23, 42, 0.06);
}
.save-hint { font-size: 13px; color: var(--color-text-secondary); }

.block-pane {
  flex-basis: 100%;
  display: flex; flex-direction: column; gap: 8px;
}
.block-alert { padding: 6px 12px; }
.block-alert :deep(.el-alert__title) { font-weight: 600; }
.block-alert :deep(.el-alert__description) { font-size: 12px; }
.dup-hint { font-size: 13px; color: var(--color-text-secondary); margin: 0 0 12px; }
.dup-alert { margin-bottom: 12px; }
.dup-alert + .dup-alert { margin-top: 0; }
:deep(.el-table .dup-row) {
  background: rgba(245, 158, 11, 0.06) !important;
}
:deep(.el-table .dup-row:hover > td.el-table__cell) {
  background: rgba(245, 158, 11, 0.12) !important;
}
</style>