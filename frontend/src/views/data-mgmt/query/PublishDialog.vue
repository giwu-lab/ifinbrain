<template>
  <el-dialog
    :model-value="modelValue"
    :title="mode === 'edit' ? t('dataMgmt.qpEditTitle') : t('dataMgmt.qyPublishTitle')"
    width="880px"
    append-to-body
    destroy-on-close
    align-center
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <div class="pp-body">
      <!-- 来源查询（已固定，不再可改） -->
      <section class="pp-section">
        <div class="form-row">
          <label class="form-label">{{ t('dataMgmt.qpPickQuery') }}</label>
          <div class="pp-query-fixed">
            <span class="pp-query-name">{{ currentQuery?.name }}</span>
            <span v-if="currentQuery?.datasourceName" class="muted small pp-query-ds">
              {{ currentQuery.datasourceName }}
            </span>
          </div>
        </div>
        <div class="form-row">
          <label class="form-label">{{ t('dataMgmt.qpMenuName') }}</label>
          <el-input v-model="menuName" :placeholder="t('dataMgmt.qpMenuNamePh')" />
        </div>
        <div class="form-row">
          <label class="form-label">{{ t('dataMgmt.qpMenuIcon') }}</label>
          <IconPicker v-model="icon" />
        </div>
        <div class="form-row">
          <label class="form-label">{{ t('dataMgmt.qpMenuSort') }}</label>
          <el-input-number v-model="sort" :min="0" :max="999" :step="1" size="small" />
        </div>
      </section>

      <!-- Step 2: edit param labels -->
      <section v-if="currentQuery && currentQuery.paramsSchema.length > 0" class="pp-section">
        <h4 class="pp-section-title">{{ t('dataMgmt.qpParamLabels') }}</h4>
        <div class="pp-fields">
          <div v-for="p in currentQuery.paramsSchema" :key="p.name" class="pp-field">
            <span class="pp-field-source">{{ p.label || p.name }}</span>
            <span class="pp-arrow">→</span>
            <el-input v-model="paramLabels[p.name]" :placeholder="p.label || p.name" size="small" />
          </div>
        </div>
      </section>

      <!-- Step 3: edit column headers + widths -->
      <section v-if="columns.length > 0" class="pp-section">
        <h4 class="pp-section-title">{{ t('dataMgmt.qpColumnProps') }}</h4>
        <div class="pp-cols">
          <div class="pp-col-head muted xs">
            <span>{{ t('dataMgmt.qpFieldSource') }}</span>
            <span>{{ t('dataMgmt.qpFieldHeader') }}</span>
            <span>{{ t('dataMgmt.qpFieldWidth') }}</span>
          </div>
          <div v-for="col in columns" :key="col.key" class="pp-col-row">
            <span class="pp-col-src mono">{{ col.label || col.key }}</span>
            <el-input v-model="colEdits[col.key].headerText"
              :placeholder="col.label || col.key" size="small" />
            <el-input-number v-model="colEdits[col.key].width"
              :min="60" :max="600" :step="20" size="small" style="width: 100%" />
          </div>
        </div>
      </section>

      <!-- Loading -->
      <div v-if="loadingPreview" class="muted small" style="padding:8px 0">
        {{ t('dataMgmt.qpLoadingColumns') }}
      </div>
    </div>

    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="publishing" :disabled="!canSubmit" @click="onSubmit">
        {{ mode === 'edit' ? t('dataMgmt.qpSaveBtn') : t('dataMgmt.qpPublishBtn') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { queryApi, type QueryItem, type QueryColumn } from '@/api/query';
import { queryPageApi, parseConfig, type QueryPageConfig } from '@/api/queryPage';
import IconPicker from '@/components/IconPicker.vue';

const { t } = useI18n();

export interface EditTarget {
  menuId: number;
  menuName: string;
  queryId: number;
  icon?: string | null;
  sort?: number;
  config: QueryPageConfig;
}

const props = defineProps<{
  modelValue: boolean;
  queries: QueryItem[];
  publishing?: boolean;
  /** 'edit' 编辑已发布页；'create' 新发布 */
  mode?: 'create' | 'edit';
  /** 编辑模式下，预填当前 menu 信息 */
  target?: EditTarget | null;
  /** 新发布模式下，要发布的 query id（必需） */
  queryId?: number | null;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void;
  (e: 'submit', payload: { queryId: number; menuName: string; icon: string; sort: number; configJson: string }): void;
  (e: 'update', payload: { menuId: number; menuName: string; icon: string; sort: number; configJson: string }): void;
}>();

const selectedQueryId = ref<number | null>(null);
const menuName = ref('');
const icon = ref<string>('Document');
const sort = ref<number>(99);
const paramLabels = ref<Record<string, string>>({});
const colEdits = ref<Record<string, { headerText: string; width: number | null }>>({});
const columns = ref<QueryColumn[]>([]);
const loadingPreview = ref(false);

const currentQuery = computed<QueryItem | undefined>(() =>
  props.queries.find(q => q.id === selectedQueryId.value));

const canSubmit = computed(() =>
  !!selectedQueryId.value && menuName.value.trim().length > 0);

watch(() => props.modelValue, async (open) => {
  if (open) await reset();
});

async function reset() {
  selectedQueryId.value = null;
  menuName.value = '';
  icon.value = 'Document';
  sort.value = 99;
  paramLabels.value = {};
  colEdits.value = {};
  columns.value = [];

  if (props.mode === 'edit' && props.target) {
    // 编辑模式：用 target 预填 queryId + menuName + icon + sort
    selectedQueryId.value = props.target.queryId;
    menuName.value = props.target.menuName;
    icon.value = props.target.icon || 'Document';
    sort.value = props.target.sort ?? 99;
    // 从服务端拿最新的 config（target.config 可能为空）
    const cfg = await queryPageApi.detail(props.target.menuId).then(d => parseConfig(d.configJson)).catch(() => props.target!.config);
    await loadFromExisting(cfg);
  } else if (props.queryId != null) {
    // 新发布（从 query list 行 [发布] 进入）：用 props.queryId 锁定
    selectedQueryId.value = props.queryId;
    const q = currentQuery.value;
    if (q) {
      menuName.value = q.name;
      icon.value = 'Document';
      sort.value = 99;
      await loadColumns(q);
    }
  }
}

/** 编辑模式：用 cfg 预填 colEdits + paramLabels，再用 query 跑 preview 拿 columns */
async function loadFromExisting(cfg: QueryPageConfig) {
  if (!selectedQueryId.value) return;
  const q = currentQuery.value;
  if (!q) return;
  // 默认 param labels = param.label || param.name
  for (const p of q.paramsSchema) paramLabels.value[p.name] = p.label || p.name;
  // 覆盖已存在的自定义 label
  for (const [k, v] of Object.entries(cfg.paramLabels || {})) {
    paramLabels.value[k] = v;
  }
  await loadColumns(q);
  // 覆盖 colEdits
  for (const [k, v] of Object.entries(cfg.columns || {})) {
    if (colEdits.value[k]) {
      colEdits.value[k].headerText = v.headerText || colEdits.value[k].headerText;
      colEdits.value[k].width = v.width ?? null;
    }
  }
}

async function onPickQuery() {
  paramLabels.value = {};
  colEdits.value = {};
  columns.value = [];
  if (!selectedQueryId.value) return;
  const q = currentQuery.value;
  if (!q) return;
  // 默认菜单名 = query 名
  menuName.value = q.name;
  // 默认 param labels = param.label || param.name
  for (const p of q.paramsSchema) paramLabels.value[p.name] = p.label || p.name;
  await loadColumns(q);
}

async function loadColumns(q: QueryItem) {
  loadingPreview.value = true;
  try {
    const defaults: Record<string, string> = {};
    for (const p of q.paramsSchema) defaults[p.name] = p.defaultValue || '';
    const res = await queryApi.run(q.id, defaults);
    columns.value = res.columns;
    // 用现有 colEdits 填充（编辑模式可能已设）
    for (const c of res.columns) {
      if (!colEdits.value[c.key]) {
        colEdits.value[c.key] = {
          headerText: c.label || c.key,
          width: null,
        };
      }
    }
  } catch (e: any) {
    ElMessage.warning(e?.response?.data?.message || t('dataMgmt.qpPreviewFailed'));
  } finally {
    loadingPreview.value = false;
  }
}

function buildConfig(): string {
  // 只收集非默认的覆盖项（避免保存过多无意义字段）
  const paramLabelsFinal: Record<string, string> = {};
  if (currentQuery.value) {
    for (const p of currentQuery.value.paramsSchema) {
      const label = paramLabels.value[p.name] ?? '';
      const defaultLabel = p.label || p.name;
      if (label && label !== defaultLabel) paramLabelsFinal[p.name] = label;
    }
  }
  const columnsFinal: Record<string, { headerText: string; width: number | null }> = {};
  for (const c of columns.value) {
    const edit = colEdits.value[c.key];
    const defaultHeader = c.label || c.key;
    const isCustomHeader = edit?.headerText && edit.headerText !== defaultHeader;
    const isCustomWidth = edit?.width != null;
    if (isCustomHeader || isCustomWidth) {
      columnsFinal[c.key] = {
        headerText: edit.headerText || defaultHeader,
        width: edit.width ?? null,
      };
    }
  }
  return JSON.stringify({ paramLabels: paramLabelsFinal, columns: columnsFinal });
}

function onSubmit() {
  if (!canSubmit.value || !selectedQueryId.value) return;
  const payload = {
    menuName: menuName.value.trim(),
    icon: (icon.value || 'Document').trim(),
    sort: sort.value ?? 99,
    configJson: buildConfig(),
  };
  if (props.mode === 'edit' && props.target) {
    emit('update', { menuId: props.target.menuId, ...payload });
  } else {
    emit('submit', { queryId: selectedQueryId.value, ...payload });
  }
}
</script>

<style scoped>
/*
 * 颜色一律走 token（AGENTS §7.3.4）：本文件原先写死 #F4F4EE / #FFFFFF /
 * #1A1A1A 等亮色值，暗色模式下这些底色会原样透出，变成刺眼亮块。
 */
.pp-body { display: flex; flex-direction: column; gap: 18px; }
.pp-section { display: flex; flex-direction: column; gap: 10px; }
.pp-section-title {
  font-size: 12px; font-weight: 600; color: var(--color-text-secondary);
  letter-spacing: 0.04em; margin: 0; text-transform: none;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--color-border-soft);
}
.form-row { display: flex; flex-direction: column; gap: 6px; }
.form-label { font-size: 13px; font-weight: 500; color: var(--color-text); }

.pp-query-fixed {
  display: flex; align-items: center; gap: 12px;
  padding: 6px 10px;
  background: var(--color-bg);
  border-radius: 3px;
  font-size: 13px;
  flex-wrap: wrap;
}
.pp-query-name {
  font-weight: 600; color: var(--color-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  min-width: 0; flex: 0 1 auto;
}
.pp-query-ds {
  font-family: var(--font-mono);
  font-size: 12px;
  background: var(--color-bg-elev);
  padding: 1px 6px; border-radius: 2px;
  border: 1px solid var(--color-border);
  flex-shrink: 0;
}

/* Param labels */
.pp-fields { display: flex; flex-direction: column; gap: 8px; }
.pp-field {
  display: grid;
  grid-template-columns: 120px 16px 1fr;
  align-items: center;
  gap: 8px;
}
.pp-field-source {
  font-size: 13px; color: var(--color-text-secondary); font-family: var(--font-mono);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.pp-arrow { color: var(--color-text-muted); text-align: center; }

/* Columns */
.pp-cols { display: flex; flex-direction: column; gap: 4px; max-height: 300px; overflow-y: auto; }
.pp-col-head, .pp-col-row {
  display: grid;
  grid-template-columns: 140px 1fr 150px;
  gap: 10px;
  align-items: center;
}
.pp-col-head { padding: 0 0 6px; border-bottom: 1px solid var(--color-border-soft); }
.pp-col-head > span:first-child { padding-left: 4px; }
.pp-col-row { padding: 4px 0; }
.pp-col-src {
  font-size: 12px; color: var(--color-text-secondary);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  padding-left: 4px;
  font-family: var(--font-mono);
}
.mono { font-family: var(--font-mono); }
.muted { color: var(--color-text-muted); }
.small { font-size: 12px; }
.xs { font-size: 11px; }
</style>