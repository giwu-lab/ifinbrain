<script setup lang="ts">
/**
 * 报表设计器。
 *
 * 设计器能做的：绑定查询、挑查询头字段与顺序、调列宽/对齐/冻结、调分页护栏。
 * 设计器**不能**做的：改 SQL。SQL 的唯一真源是 sys_query，要改去「数据管理 → 查询」——
 * 这样「报表看到的参数」和「SQL 里声明的参数」不会漂移。
 *
 * 新建与编辑共用本页面（路由 /bi/report/design/new 与 /bi/report/design/:id）。
 */
import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Back, Check } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import {
  reportApi,
  type ColumnCfg,
  type HeaderLayout,
  type Report,
  type ReportColumn,
  type ReportParamSchema,
  type ReportQueryOption,
} from '@/api/report';
import { clampWidth, mergeColumns, visibleParams } from '@/utils/report/header';

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const isNew = computed(() => route.params.id === 'new');
const reportId = computed(() => (isNew.value ? null : Number(route.params.id)));

const report = ref<Report | null>(null);
const queryOptions = ref<ReportQueryOption[]>([]);

/** 表单字段 */
const code = ref('');
const name = ref('');
const description = ref('');
const queryId = ref<number | null>(null);
const pageSize = ref(200);
const maxRows = ref(50000);
const headerLayout = ref<HeaderLayout>({ visible: null, collapsed: false, columns: 4 });
const columnCfg = ref<ColumnCfg[]>([]);
const defaultParams = ref<Record<string, string>>({});

const saving = ref(false);
const loading = ref(false);

/** 预览结果 */
const previewCols = ref<ReportColumn[]>([]);
const previewRows = ref<any[][]>([]);
const previewing = ref(false);
const previewErr = ref('');

/** 当前绑定查询的参数 schema */
const paramSchema = computed<ReportParamSchema[]>(
  () => queryOptions.value.find((o) => o.id === queryId.value)?.params ?? []
);

/** 查询头候选 = schema 全量（设计态允许看到全部，由 visible 决定哪些进查询头） */
const headerCandidates = computed(() => paramSchema.value);

/** 已勾选进查询头的参数（按 visible 顺序） */
const selectedNames = computed<string[]>(() => {
  const v = headerLayout.value.visible;
  if (!v || !v.length) return paramSchema.value.map((p) => p.name);
  return v.filter((n) => paramSchema.value.some((p) => p.name === n));
});

function isSelected(name: string) {
  return selectedNames.value.includes(name);
}

function toggleParam(name: string, on: boolean) {
  const cur = selectedNames.value;
  const next = on ? [...cur, name] : cur.filter((n) => n !== name);
  headerLayout.value = { ...headerLayout.value, visible: next };
}

function moveParam(name: string, dir: -1 | 1) {
  const cur = [...selectedNames.value];
  const i = cur.indexOf(name);
  const j = i + dir;
  if (i < 0 || j < 0 || j >= cur.length) return;
  [cur[i], cur[j]] = [cur[j], cur[i]];
  headerLayout.value = { ...headerLayout.value, visible: cur };
}

/** 预览列：优先用已保存的列配置，没有就按查询结果列自动生成 */
const effectiveCols = computed<ColumnCfg[]>(() =>
  mergeColumns(previewCols.value, columnCfg.value)
);

async function load() {
  loading.value = true;
  try {
    queryOptions.value = await reportApi.queryOptions();
    if (isNew.value) {
      report.value = null;
      pageSize.value = 200;
      maxRows.value = 50000;
      headerLayout.value = { visible: null, collapsed: false, columns: 4 };
      columnCfg.value = [];
      return;
    }
    const r = await reportApi.detail(reportId.value!);
    report.value = r;
    code.value = r.code;
    name.value = r.name;
    description.value = r.description ?? '';
    queryId.value = r.queryId;
    pageSize.value = r.pageSize;
    maxRows.value = r.maxRows;
    headerLayout.value = r.header ?? { visible: null, collapsed: false, columns: 4 };
    columnCfg.value = r.columns ?? [];
    defaultParams.value = r.defaultParams ?? {};
    await doPreview();
  } finally {
    loading.value = false;
  }
}

/**
 * 试跑：调真实 SQL 取首屏 50 行，用于确认列与数据。
 *
 * 走 reportApi.preview（按 queryId 取数），所以**新建报表不用先保存**。
 * 设计时本来就要反复调参数看列宽合不合适，每次都先存一条草稿会污染 sys_report，
 * 而且那些草稿用户自己都不敢删。试跑与保存彻底解耦。
 */
async function doPreview() {
  if (!queryId.value) {
    previewErr.value = t('report.queryRequired');
    return;
  }
  previewing.value = true;
  previewErr.value = '';
  try {
    const resp = await reportApi.preview(queryId.value, defaultParams.value, 50);
    previewCols.value = resp.columns ?? [];
    previewRows.value = resp.rows ?? [];
    // 首次预览且没有列配置 → 用结果列自动生成，省一次手动配置
    if (!columnCfg.value.length && previewCols.value.length) {
      columnCfg.value = previewCols.value.map((c) => ({
        key: c.key,
        label: c.label,
        width: clampWidth(c.width),
        align: c.type === 'number' ? 'right' : 'left',
        fixed: false,
        visible: true,
      }));
    }
  } catch (e: any) {
    previewErr.value = e?.message || t('report.previewFailed');
  } finally {
    previewing.value = false;
  }
}

async function onSave() {
  if (!name.value.trim()) {
    ElMessage.warning(t('report.nameRequired'));
    return;
  }
  if (!queryId.value) {
    ElMessage.warning(t('report.queryRequired'));
    return;
  }
  saving.value = true;
  try {
    const payload = {
      name: name.value.trim(),
      description: description.value,
      queryId: queryId.value,
      header: headerLayout.value,
      columns: columnCfg.value,
      defaultParams: defaultParams.value,
      pageSize: pageSize.value,
      maxRows: maxRows.value,
    };
    if (isNew.value) {
      const created = await reportApi.create({ ...payload, code: code.value.trim() || autoCode() });
      ElMessage.success(t('report.saved'));
      router.replace(`/bi/report/design/${created.id}`);
    } else {
      await reportApi.update(reportId.value!, payload);
      ElMessage.success(t('report.saved'));
      await load();
    }
  } finally {
    saving.value = false;
  }
}

/** 未填 code 时用名称生成一个稳定 slug；中文名退化为 report_<时间戳> */
function autoCode() {
  const slug = name.value
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 40);
  return slug || `report_${Date.now()}`;
}

function onBack() {
  router.push('/bi/report');
}

onMounted(load);
watch(queryId, () => {
  previewCols.value = [];
  previewRows.value = [];
  columnCfg.value = [];
});
</script>

<template>
  <div class="rpd-page" v-loading="loading">
    <div class="page-header rpd-header">
      <div class="rpd-head-left">
        <el-button link :icon="Back" @click="onBack">{{ t('common.back') }}</el-button>
        <div>
          <h1 class="page-title">{{ isNew ? t('report.create') : t('report.design') }}</h1>
          <p class="page-desc muted">{{ t('report.designDesc') }}</p>
        </div>
      </div>
      <div class="header-actions">
        <!--
          试跑按 queryId 取数，新建报表没保存也能预览，所以这里不再按 report 显隐 ——
          之前 v-if="report" 会让新建时整个按钮消失，用户既看不到功能，
          也看不到 doPreview 里「请先选查询」的提示。
          disabled 代替显隐：功能始终可见，没就绪时置灰。
        -->
        <el-button
          :loading="previewing"
          :disabled="!queryId"
          @click="doPreview"
        >
          {{ t('report.preview') }}
        </el-button>
        <el-button
          v-if="auth.hasPerm(isNew ? 'report:create' : 'report:update')"
          type="primary"
          :icon="Check"
          :loading="saving"
          @click="onSave"
        >
          {{ t('common.save') }}
        </el-button>
      </div>
    </div>

    <div class="rpd-grid">
      <!-- 左：基本设置 + 查询头 -->
      <div class="rpd-col rpd-col-left">
        <section class="rpd-block">
          <h3 class="rpd-block-title">{{ t('report.basic') }}</h3>
          <el-form label-width="110px" label-position="left">
            <el-form-item :label="t('report.col.code')" v-if="isNew">
              <el-input v-model="code" :placeholder="t('report.codePlaceholder')" />
            </el-form-item>
            <el-form-item :label="t('report.col.name')" required>
              <el-input v-model="name" />
            </el-form-item>
            <el-form-item :label="t('report.col.desc')">
              <el-input v-model="description" type="textarea" :rows="2" />
            </el-form-item>
            <el-form-item :label="t('report.col.query')" required>
              <el-select v-model="queryId" :placeholder="t('report.pickQuery')" style="width: 100%">
                <el-option
                  v-for="o in queryOptions"
                  :key="o.id"
                  :label="o.name"
                  :value="o.id"
                />
              </el-select>
              <div class="rpd-hint">{{ t('report.queryHint') }}</div>
            </el-form-item>
          </el-form>
        </section>

        <section class="rpd-block">
          <h3 class="rpd-block-title">{{ t('report.queryHeader') }}</h3>
          <div v-if="!paramSchema.length" class="rpd-hint">{{ t('report.noParams') }}</div>
          <div v-else class="rpd-params">
            <div class="rpd-param-cols">
              <span class="rpd-param-label">{{ t('report.headerColumns') }}</span>
              <el-input-number
                v-model="headerLayout.columns"
                :min="1"
                :max="6"
                size="small"
                controls-position="right"
              />
            </div>
            <div v-for="p in headerCandidates" :key="p.name" class="rpd-param">
              <el-checkbox
                :model-value="isSelected(p.name)"
                @update:model-value="(v: any) => toggleParam(p.name, !!v)"
              >
                {{ p.label || p.name }}
              </el-checkbox>
              <span class="rpd-param-name mono">{{ p.name }}</span>
              <span class="rpd-param-ops">
                <el-button link :disabled="!isSelected(p.name)" @click="moveParam(p.name, -1)">↑</el-button>
                <el-button link :disabled="!isSelected(p.name)" @click="moveParam(p.name, 1)">↓</el-button>
              </span>
            </div>
          </div>
        </section>

        <section class="rpd-block">
          <h3 class="rpd-block-title">{{ t('report.perf') }}</h3>
          <el-form label-width="110px" label-position="left">
            <el-form-item :label="t('report.col.pageSize')">
              <el-input-number v-model="pageSize" :min="20" :max="2000" :step="20" controls-position="right" />
            </el-form-item>
            <el-form-item :label="t('report.col.maxRows')">
              <el-input-number v-model="maxRows" :min="0" :step="1000" controls-position="right" />
              <div class="rpd-hint">{{ t('report.maxRowsHint') }}</div>
            </el-form-item>
          </el-form>
        </section>
      </div>

      <!-- 右：列配置 + 预览 -->
      <div class="rpd-col rpd-col-right">
        <section class="rpd-block rpd-block-grow">
          <div class="rpd-block-head">
            <h3 class="rpd-block-title">{{ t('report.columns') }}</h3>
            <span class="rpd-hint">{{ t('report.columnsHint') }}</span>
          </div>

          <div v-if="previewErr" class="rpd-err">{{ previewErr }}</div>

          <div v-if="!effectiveCols.length" class="rpd-empty" v-loading="previewing">
            {{ t('report.noColumns') }}
          </div>

          <div v-else class="rpd-cols" v-loading="previewing">
            <div v-for="c in effectiveCols" :key="c.key" class="rpd-col-row">
              <span class="rpd-col-key mono">{{ c.key }}</span>
              <el-input v-model="c.label" size="small" class="rpd-col-label" />
              <el-input-number
                v-model="c.width"
                :min="80"
                :max="600"
                :step="20"
                size="small"
                controls-position="right"
                class="rpd-col-w"
              />
              <el-select v-model="c.align" size="small" class="rpd-col-align">
                <el-option value="left" :label="t('report.alignLeft')" />
                <el-option value="center" :label="t('report.alignCenter')" />
                <el-option value="right" :label="t('report.alignRight')" />
              </el-select>
              <el-checkbox v-model="c.fixed" size="small">{{ t('report.fixed') }}</el-checkbox>
            </div>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
/*
 * 高度策略：**不使用 height: 100%**。
 *
 * 本页走 BasicLayout，高度链是
 *   .layout-root(100vh) → el-main.main → .main-inner
 * 而 .main-inner 只有 `min-height: 100%`（见 SidebarLayout.vue），
 * 它自身高度是 auto —— 对子元素来说百分比基准不明确。
 * 于是 `height: 100%` 退化成 auto，下面的 .rpd-grid（flex: 1 1 0）
 * 拿到一个 auto 父高 + flex-basis 0 ⇒ 整块高度塌成 0 ⇒ 内容全部不可见，
 * 只剩 flex: 0 0 auto 的 header 还在（表现就是「有标题有按钮，下面一片空白」）。
 *
 * 改用 min-height + flex-basis:auto：无论高度链通断都按内容高度渲染，
 * 链通时（视口够高）仍会填满。
 */
.rpd-page {
  padding: 16px;
  min-height: 100%;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
}

.rpd-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex: 0 0 auto;
}

.rpd-head-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

/*
 * flex-basis 用 auto 而不是 0：父高不确定时，basis 0 会让整块塌成 0 高。
 * min-height 420px 是兜底，保证两列至少有一段可见高度（内部滚动交给 .main 兜）。
 */
.rpd-grid {
  flex: 1 1 auto;
  min-height: 420px;
  display: grid;
  grid-template-columns: 340px 1fr;
  gap: 16px;
  margin-top: 16px;
}

.rpd-col {
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.rpd-block {
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-bg-elev);
  padding: 14px 16px;
}

.rpd-block-grow {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.rpd-block-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.rpd-block-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text);
}

.rpd-block-head .rpd-block-title {
  margin: 0;
}

.rpd-hint {
  font-size: 12px;
  color: var(--color-text-muted);
  line-height: 1.5;
}

.rpd-params {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.rpd-param-cols {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--color-border-soft);
}

.rpd-param-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.rpd-param {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rpd-param-name {
  font-size: 12px;
  color: var(--color-text-muted);
  flex: 1 1 auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rpd-param-ops {
  flex: 0 0 auto;
}

.rpd-cols {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.rpd-col-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px solid var(--color-border-soft);
}

.rpd-col-key {
  width: 150px;
  flex: 0 0 auto;
  font-size: 12px;
  color: var(--color-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rpd-col-label {
  flex: 1 1 auto;
  min-width: 100px;
}

.rpd-col-w {
  width: 130px;
  flex: 0 0 auto;
}

.rpd-col-align {
  width: 90px;
  flex: 0 0 auto;
}

.rpd-err {
  margin-bottom: 10px;
  padding: 8px 12px;
  border: 1px solid var(--color-danger);
  border-radius: 6px;
  background: var(--color-danger-soft);
  color: var(--color-danger);
  font-size: var(--font-size-sm);
}

.rpd-empty {
  flex: 1 1 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}
</style>
