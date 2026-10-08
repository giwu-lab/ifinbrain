import { computed, reactive, ref } from 'vue';
import { dataImportApi } from '@/api/dataImport';
import { datasourceApi } from '@/api/datasource';
import type { Datasource } from '@/api/datasource';
import type {
  ImportColumn,
  ImportMapping,
  ImportOptions,
  ImportPreview,
  ImportResult,
  ImportSchema,
  ImportValidate,
} from '@/api/dataImport';

/**
 * 数据导入向导的共享状态。
 *
 * 用模块级单例而不是 Pinia：这个状态只在 /data-mgmt/import 一个页面里活着，
 * 四个步骤组件同时要读同一份 sessionId / mappings，
 * 走 composable 比 props + emit 逐层透传少一半噪音。
 */

const datasources = ref<Datasource[]>([]);
const dsLoading = ref(false);

/** 第 1 步 */
const datasourceId = ref<number | null>(null);
const schema = ref('');
const table = ref('');
const schemaDto = ref<ImportSchema | null>(null);
const schemaLoading = ref(false);

/** 第 2 步 */
const file = ref<File | null>(null);
const hasHeader = ref(true);
const encoding = ref('auto');
const sheet = ref('');
const uploading = ref(false);
/** 解析失败原因，就地显示在第 2 步（preview 请求已设 _silent，避免再弹一次 toast） */
const previewError = ref('');

/** 第 3 步 */
const preview = ref<ImportPreview | null>(null);
const mappings = ref<ImportMapping[]>([]);
const options = reactive<Required<Omit<ImportOptions, 'mode'>> & { mode: 'append' }>({
  mode: 'append',
  emptyAsNull: true,
  truncateString: true,
  skipEmptyRow: true,
  batchSize: 1000,
});
const validating = ref(false);
const validateResult = ref<ImportValidate | null>(null);

/** 第 4 步 */
const committing = ref(false);
const commitResult = ref<ImportResult | null>(null);

/** 当前步骤 0~3 */
const step = ref(0);

/** 目标表可映射列（自增 / 生成列排除） */
const targetColumns = computed<ImportColumn[]>(() =>
  (preview.value?.columns ?? []).filter((c) => !c.generated),
);

/** 被映射到的目标列名集合 —— 同一个目标列只能被映射一次 */
const usedTargets = computed<Set<string>>(
  () =>
    new Set(
      mappings.value
        .filter((m) => m.enabled && m.targetColumn)
        .map((m) => m.targetColumn as string),
    ),
);

/** 表头归一化：「用户 姓名」≡「user_name」≡「用户姓名」都能自动配对 */
function normKey(s: string): string {
  return s
    .replace(/[\s_\-()（）[\].,]/g, '')
    .toLowerCase();
}

/** 按归一化名做同名自动映射；已被占用的目标列不会被覆盖 */
function autoMap(): void {
  if (!preview.value) return;
  const taken = new Set<string>();
  const next: ImportMapping[] = preview.value.headers.map((h) => {
    const hit = targetColumns.value.find(
      (c) => !taken.has(c.name) && normKey(c.name) === normKey(h),
    );
    if (hit) {
      taken.add(hit.name);
      return { sourceHeader: h, targetColumn: hit.name, enabled: true };
    }
    return { sourceHeader: h, targetColumn: null, enabled: false };
  });
  mappings.value = next;
}

async function loadDatasources(): Promise<void> {
  if (datasources.value.length) return;
  dsLoading.value = true;
  try {
    // 下拉要看到全部数据源，所以走不分页的 listAll
    datasources.value = await datasourceApi.listAll();
  } finally {
    dsLoading.value = false;
  }
}

/** 拉取目标库的表结构（可写列 + schema 树） */
async function loadSchema(id: number): Promise<void> {
  schemaLoading.value = true;
  schemaDto.value = null;
  schema.value = '';
  table.value = '';
  try {
    schemaDto.value = await dataImportApi.columns(id);
  } finally {
    schemaLoading.value = false;
  }
}

/** 换文件 / 换目标表：作废已有解析结果与映射，避免界面显示的是上一份文件的映射 */
async function invalidateSource(): Promise<void> {
  const old = preview.value?.sessionId;
  preview.value = null;
  mappings.value = [];
  validateResult.value = null;
  commitResult.value = null;
  step.value = 0;
  if (old) {
    // 会话留着也是白占后端内存，用户主动丢弃
    dataImportApi.discardSession(old).catch(() => {});
  }
}

async function doPreview(): Promise<boolean> {
  if (!file.value || !datasourceId.value) return false;
  uploading.value = true;
  previewError.value = '';
  try {
    const res = await dataImportApi.preview({
      datasourceId: datasourceId.value,
      schema: schema.value,
      table: table.value,
      sheet: sheet.value || undefined,
      hasHeader: hasHeader.value,
      encoding: encoding.value,
      file: file.value,
    });
    // 没让用户选 sheet 就退回空值，下次解析仍走「第一个工作表」，
    // 免得把上一次选中的 sheet 悄悄带到新文件上
    if (sheet.value && !res.sheetNames.includes(sheet.value)) sheet.value = '';
    preview.value = res;
    autoMap();
    validateResult.value = null;
    commitResult.value = null;
    return true;
  } catch (e: any) {
    previewError.value = e?.message || 'parse failed';
    return false;
  } finally {
    uploading.value = false;
  }
}

async function doValidate(): Promise<boolean> {
  if (!preview.value) return false;
  validating.value = true;
  try {
    validateResult.value = await dataImportApi.validate({
      sessionId: preview.value.sessionId,
      mappings: mappings.value,
      options: { ...options },
    });
    return validateResult.value.valid;
  } finally {
    validating.value = false;
  }
}

async function doCommit(): Promise<boolean> {
  if (!preview.value) return false;
  committing.value = true;
  try {
    commitResult.value = await dataImportApi.commit({
      sessionId: preview.value.sessionId,
      mappings: mappings.value,
      options: { ...options },
    });
    // commit 后端会销毁会话，前端也清掉，避免用户点「上一步」拿到过期会话
    preview.value = null;
    return true;
  } finally {
    committing.value = false;
  }
}

function resetAll(): void {
  void invalidateSource();
  file.value = null;
  sheet.value = '';
  hasHeader.value = true;
  encoding.value = 'auto';
  previewError.value = '';
  step.value = 0;
}

export function useImportWizard() {
  return {
    step,
    datasources,
    dsLoading,
    datasourceId,
    schema,
    table,
    schemaDto,
    schemaLoading,
    file,
    hasHeader,
    encoding,
    sheet,
    uploading,
    previewError,
    preview,
    mappings,
    options,
    validating,
    validateResult,
    committing,
    commitResult,
    targetColumns,
    usedTargets,
    autoMap,
    loadDatasources,
    loadSchema,
    invalidateSource,
    doPreview,
    doValidate,
    doCommit,
    resetAll,
  };
}

/** 人类可读的文件体积 */
export function formatSize(bytes?: number): string {
  if (!bytes || bytes < 0) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/** 选中的目标表全名（预检确认时展示，避免导错表） */
export function useTargetLabel() {
  return computed(() => (schema.value && table.value ? `${schema.value}.${table.value}` : '—'));
}