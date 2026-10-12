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
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { ArrowDown, ArrowUp, Back, Check, Close, RefreshLeft } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import {
  reportApi,
  type ColumnCfg,
  type HeaderControl,
  type HeaderFieldCfg,
  type HeaderLayout,
  type Report,
  type ReportColumn,
  type ReportParamSchema,
  type ReportQueryOption,
} from '@/api/report';
import {
  HEADER_CONTROLS,
  cellText,
  controlOf,
  firstMissingRequired,
  initialParams,
  mergeColumns,
  optionsOf,
  visibleParams,
} from '@/utils/report/header';

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
const pageSize = ref(50);
const maxRows = ref(50000);
const headerLayout = ref<HeaderLayout>({ visible: null, collapsed: false, columns: 4 });
/**
 * 列配置草稿 —— 设计器里唯一可编辑的真源。
 *
 * 这里踩过一个很隐蔽的 Vue 坑：原先把 v-model 直接绑在
 * `computed(() => mergeColumns(...))` 的返回对象上，而 mergeColumns 每次重算都
 * `.map()` 出一份**全新的对象数组**。于是输入框里敲的字落在临时对象上，
 * `columnCfg` 本身没变，任何一次重算（换查询 / 切参数 / 点预览 / 任何响应式更新）
 * 新对象一生成，改动就被整体丢弃 —— 用户看到的就是「列名列宽对齐冻结全都改不动」。
 *
 * 现在：预览时用 mergeColumns 把「结果列 ∩ 已有草稿」合并回草稿，之后渲染与
 * 编辑都只认这一份 ref，v-model 才真正写得住。已有的人工配置按 key 保留，
 * SQL 新增的列自动带默认值补进来 —— 合并语义一点没丢。
 */
const colDraft = ref<ColumnCfg[]>([]);
const defaultParams = ref<Record<string, string>>({});

const saving = ref(false);
const loading = ref(false);

/** 预览结果 */
const previewCols = ref<ReportColumn[]>([]);
const previewRows = ref<any[][]>([]);
const previewing = ref(false);
const previewErr = ref('');
/** 数据预览抽屉：试跑成功后自动拉开，用户看一眼就能关掉 */
const previewDrawer = ref(false);

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

/**
 * 真正拿去**填值**的参数（按 visible 顺序）。
 *
 * 复用 visibleParams —— 与播放页同一个纯函数，保证「设计时排的顺序」就是
 * 「跑起来渲染的顺序」，不会出现两处各写一套推导逻辑的偏差。
 *
 * 这个 computed 是补上「预览数据没反应」的关键一环：之前 QueryHeader 组件
 * import 了却从没在模板里渲染，设计器上根本没有输入框，defaultParams 恒为 {}，
 * 必填参数一个都过不了 firstMissingRequired，用户点「预览数据」只弹个 warning，
 * 页面纹丝不动 —— 表现就是「按钮没效果」。
 */
const selectedParams = computed<ReportParamSchema[]>(() =>
  visibleParams(paramSchema.value, headerLayout.value)
);

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

/** 排序按钮的禁用判据：没选中的排不了，头尾也到边了 */
function canMove(name: string, dir: -1 | 1): boolean {
  const cur = selectedNames.value;
  const i = cur.indexOf(name);
  if (i < 0) return false;
  const j = i + dir;
  return j >= 0 && j < cur.length;
}

// ===================== 控件类型覆盖 =====================

/** 该参数当前的控件（覆盖优先，回落 schema）—— 播放页用的是同一个函数 */
function controlOfParam(p: ReportParamSchema): HeaderControl {
  return controlOf(p, headerLayout.value.fields);
}

/** 是否存在显式覆盖（用于决定「恢复默认」按钮要不要露出来） */
function hasControlOverride(name: string): boolean {
  return !!headerLayout.value.fields?.[name]?.type;
}

/**
 * 改某个参数的控件配置。
 *
 * 整体替换 headerLayout 而不是就地 mutate —— colDraft / colDraft 的 v-model 已经踩过
 * 一遍「对象被换掉、正在输入的内容蒸发」的坑，这里保持同一种改法：换新对象。
 */
function patchField(name: string, patch: HeaderFieldCfg) {
  const cur = headerLayout.value.fields?.[name] ?? {};
  headerLayout.value = {
    ...headerLayout.value,
    fields: { ...(headerLayout.value.fields ?? {}), [name]: { ...cur, ...patch } },
  };
}

function setControl(name: string, type: HeaderControl) {
  patchField(name, { type });
}

function setOptions(name: string, options: string[]) {
  // 去空白 + 去重：候选值是给用户点的，重复项只会让人以为有语义差别
  const clean = Array.from(
    new Set((options ?? []).map((o) => String(o ?? '').trim()).filter(Boolean))
  );
  patchField(name, { options: clean });
}

/** 恢复跟随 schema：整个 fields[name] 删掉，而不是把 type 写回 schema 的值 */
function resetControl(name: string) {
  const next = { ...(headerLayout.value.fields ?? {}) };
  delete next[name];
  headerLayout.value = { ...headerLayout.value, fields: next };
}

/**
 * 数字框的读写代理。
 *
 * defaultParams 是 Record<string, string>（随报表落库的就是字符串），
 * 而 el-input-number 只吃 number | undefined。硬塞字符串进去它会告警，
 * 所以这里做一层双向代理：**显示时转数字，写回时转字符串**，真源始终是 defaultParams。
 */
const defaultNum = computed<Record<string, number | undefined>>({
  get: () => {
    const out: Record<string, number | undefined> = {};
    for (const [k, v] of Object.entries(defaultParams.value)) {
      if (v === '' || v == null) continue;
      const n = Number(v);
      if (Number.isFinite(n)) out[k] = n;
    }
    return out;
  },
  set: (next) => {
    const merged = { ...defaultParams.value };
    for (const [k, v] of Object.entries(next)) merged[k] = v == null ? '' : String(v);
    defaultParams.value = merged;
  },
});

function setDefaultNum(name: string, v: number | undefined) {
  defaultParams.value = { ...defaultParams.value, [name]: v == null ? '' : String(v) };
}

/** 预览列：优先用已保存的列配置，没有就按查询结果列自动生成 */

async function load() {
  loading.value = true;
  try {
    queryOptions.value = await reportApi.queryOptions();
    if (isNew.value) {
      report.value = null;
      pageSize.value = 50;
      maxRows.value = 50000;
      headerLayout.value = { visible: null, collapsed: false, columns: 4 };
      colDraft.value = [];
      return;
    }
    // 设计器走 /design：要求实体级 design 权限，光有 view 的人打不开设计器
    const r = await reportApi.detailForDesign(reportId.value!);
    report.value = r;
    code.value = r.code;
    name.value = r.name;
    description.value = r.description ?? '';
    queryId.value = r.queryId;
    pageSize.value = r.pageSize;
    maxRows.value = r.maxRows;
    headerLayout.value = r.header ?? { visible: null, collapsed: false, columns: 4 };
    colDraft.value = r.columns ?? [];
    // schema 默认值打底 + 报表里存过的值覆盖：没显式存过值的报表也能拿到查询自带的默认值，
    // 而不是留一个空对象。
    defaultParams.value = initialParams(paramSchema.value, r.defaultParams);
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
  // 必填参数没填就先拦一道：否则用户看到的是一张空表格，分不清是"筛没了"还是"参数没填"
  const miss = firstMissingRequired(paramSchema.value, defaultParams.value);
  if (miss) {
    const name = paramSchema.value.find((p) => p.name === miss)?.label || miss;
    previewErr.value = t('report.missingParam', { name });
    ElMessage.warning(t('report.missingParam', { name }));
    return;
  }
  previewing.value = true;
  previewErr.value = '';
  try {
    const resp = await reportApi.preview(queryId.value, defaultParams.value, 50);
    previewCols.value = resp.columns ?? [];
    previewRows.value = resp.rows ?? [];
    // 结果列是准的（SQL 改了 SELECT 列表也能自适应），把「结果列 ∩ 已有草稿」合并回草稿：
    // 已有的人工配置按 key 保留，SQL 新增的列自动带默认值补进来。
    colDraft.value = mergeColumns(previewCols.value, colDraft.value);
    // 结果在抽屉里，点了按钮如果抽屉不弹，用户的第一反应是「没生效」。
    // api 层是 _silent 的（不弹全局 toast），所以这里显式给一次汇总 + 自动拉开抽屉。
    if (!previewCols.value.length) {
      ElMessage.warning(t('report.previewEmpty'));
    } else {
      ElMessage.success(t('report.previewOk', { n: previewRows.value.length }));
      previewDrawer.value = true;
    }
  } catch (e: any) {
    previewErr.value = e?.message || t('report.previewFailed');
    ElMessage.error(previewErr.value);
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
      columns: colDraft.value,
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

/**
 * 换查询：清掉上一个查询的结果与列配置，参数重置成新查询的默认值。
 *
 * 挂在 el-select 的 @change 上而不是 watch(queryId)：load() 里也会写 queryId，
 * watch 分不清「用户换的」和「服务端载入的」，会把刚填好的 defaultParams 又冲回
 * schema 默认值 —— 新建报表选完查询直接点预览就能复现。
 */
function onQueryChange() {
  previewCols.value = [];
  previewRows.value = [];
  previewErr.value = '';
  colDraft.value = [];
  defaultParams.value = initialParams(paramSchema.value, null);
}
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
          这里**不再**放「预览数据」按钮：试跑入口已经移进查询头面板，
          紧挨着它操作的那排参数框。两个入口做同一件事，用户反而不知道该点哪个。
        -->
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

    <!--
      顶部通栏：基本设置 + 分页护栏 + 查询条件，**合成一块**。

      之前是三条各自带边框的条带，等于在同一条通栏上画了三道横线、三次标题，
      白白吃掉两段纵向空间。

      宽度也一并重定：原来用 grid auto-fit 让每项平分，结果「数据源查询」的下拉
      被拉到 1100px，而「每页行数」只有 50px —— 宽度跟内容长度彻底脱节。
      现在每项按**实际需要的宽度**给 flex-basis，只有描述吃剩余空间。
    -->
    <section class="rpd-block rpd-meta">
      <el-form label-width="92px" label-position="left">
        <div class="rpd-meta-row">
          <el-form-item v-if="isNew" :label="t('report.col.code')" class="rpd-fw-code">
            <el-input v-model="code" :placeholder="t('report.codePlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('report.col.name')" required class="rpd-fw-name">
            <el-input v-model="name" />
          </el-form-item>
          <el-form-item :label="t('report.col.query')" required class="rpd-fw-query">
            <el-select
              v-model="queryId"
              :placeholder="t('report.pickQuery')"
              style="width: 100%"
              @change="onQueryChange"
            >
              <el-option
                v-for="o in queryOptions"
                :key="o.id"
                :label="o.name"
                :value="o.id"
              />
            </el-select>
            <div class="rpd-hint">{{ t('report.queryHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('report.col.desc')" class="rpd-fw-desc">
            <el-input v-model="description" />
          </el-form-item>
        </div>

        <div class="rpd-meta-row">
          <el-form-item :label="t('report.col.pageSize')" class="rpd-fw-num">
            <el-input-number v-model="pageSize" :min="20" :max="2000" :step="20" controls-position="right" />
          </el-form-item>
          <el-form-item :label="t('report.col.maxRows')" class="rpd-fw-num">
            <el-input-number v-model="maxRows" :min="0" :step="1000" controls-position="right" />
          </el-form-item>
          <div class="rpd-meta-hint rpd-hint">{{ t('report.maxRowsHint') }}</div>
        </div>
      </el-form>
    </section>

    <!--
      中部并列：查询头字段配置 | 列配置。

      两块都是「有边框的独立面板」，直接当 grid 的两个子项，不再套一层 .rpd-col ——
      多包一层只会让内部 flex 高度链多一环，正是之前高度塌陷那类坑的来源。
    -->
    <div class="rpd-grid">
      <section class="rpd-block rpd-head-panel">
        <div class="rpd-head-top">
          <h3 class="rpd-block-title rpd-block-title-inline">{{ t('report.queryHeader') }}</h3>
          <div class="rpd-head-ops">
            <el-input-number
              v-model="headerLayout.columns"
              :min="1"
              :max="6"
              size="small"
              controls-position="right"
              class="rpd-head-cols"
            />
            <el-tooltip :content="t('report.headerColumns')" placement="top">
              <span class="rpd-head-cols-label">{{ t('report.headerColumns') }}</span>
            </el-tooltip>
            <!-- 预览按钮跟着查询条件走：它操作的就是下面这些框 -->
            <el-button
              type="primary"
              :loading="previewing"
              :disabled="!queryId"
              @click="doPreview"
            >
              {{ t('report.preview') }}
            </el-button>
          </div>
        </div>
        <div v-if="!paramSchema.length" class="rpd-hint">{{ t('report.noParams') }}</div>
        <div v-else class="rpd-params">
            <div class="rpd-hint">{{ t('report.controlHint') }}</div>
            <div v-for="p in headerCandidates" :key="p.name" class="rpd-param">
              <div class="rpd-param-row">
                <el-checkbox
                  :model-value="isSelected(p.name)"
                  @update:model-value="(v: any) => toggleParam(p.name, !!v)"
                >
                  {{ p.label || p.name }}
                </el-checkbox>
                <span class="rpd-param-name mono">{{ p.name }}</span>
                <!--
                  默认值输入框：紧跟在参数名后面。
                  之前取值区是一个独立的通栏表单，把同一批参数又画了一遍 ——
                  同一个字段在页面上出现两次，还得两边对着看才知道哪个值在生效。
                  并进参数行后「参数 → 控件 → 默认值」一行读完，且这一行填的就是
                  随报表保存的 defaultParams（保存时原样提交，不用再对��）。
                -->
                <div class="rpd-param-val">
                  <el-select
                    v-if="controlOfParam(p) === 'select'"
                    v-model="defaultParams[p.name]"
                    size="small"
                    clearable
                    filterable
                    allow-create
                    default-first-option
                    :reserve-keyword="false"
                    :placeholder="t('report.selectOptions')"
                  >
                    <el-option v-for="o in optionsOf(p, headerLayout.fields)" :key="o" :label="o" :value="o" />
                  </el-select>
                  <el-input-number
                    v-else-if="controlOfParam(p) === 'number'"
                    v-model="defaultNum[p.name]"
                    size="small"
                    controls-position="right"
                    class="rpd-cell-input"
                    @update:model-value="(v: any) => setDefaultNum(p.name, v)"
                  />
                  <el-input
                    v-else
                    v-model="defaultParams[p.name]"
                    size="small"
                    clearable
                    @update:model-value="(v: string) => (defaultParams[p.name] = v)"
                  />
                </div>
                <el-select
                  :model-value="controlOfParam(p)"
                  size="small"
                  class="rpd-param-ctrl"
                  @update:model-value="(v: any) => setControl(p.name, v as HeaderControl)"
                >
                  <el-option
                    v-for="c in HEADER_CONTROLS"
                    :key="c"
                    :label="t(`report.ctrl.${c}`)"
                    :value="c"
                  />
                </el-select>
                <el-tooltip
                  v-if="hasControlOverride(p.name)"
                  :content="t('report.resetControl')"
                  placement="top"
                >
                  <el-button link :icon="RefreshLeft" @click="resetControl(p.name)" />
                </el-tooltip>
                <el-tooltip :content="t('report.moveUp')" placement="top">
                  <el-button
                    link
                    :icon="ArrowUp"
                    :disabled="!canMove(p.name, -1)"
                    @click="moveParam(p.name, -1)"
                  />
                </el-tooltip>
                <el-tooltip :content="t('report.moveDown')" placement="top">
                  <el-button
                    link
                    :icon="ArrowDown"
                    :disabled="!canMove(p.name, 1)"
                    @click="moveParam(p.name, 1)"
                  />
                </el-tooltip>
              </div>

              <!--
                只有下拉框才有候选值可编，其余控件这里不占位
              -->
              <div v-if="controlOfParam(p) === 'select'" class="rpd-param-opts">
                <el-select
                  :model-value="optionsOf(p, headerLayout.fields)"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  :reserve-keyword="false"
                  size="small"
                  :placeholder="t('report.selectOptions')"
                  @update:model-value="(v: any) => setOptions(p.name, v)"
                />
                <div v-if="!optionsOf(p, headerLayout.fields).length" class="rpd-warn">
                  {{ t('report.selectOptionsEmpty') }}
                </div>
              </div>
            </div>
          </div>
      </section>

      <!-- 右：列配置。项目标准列表样式：.table-wrap 容器 + el-table（AGENTS §7.1 / §8） -->
      <div class="table-wrap rpd-cols-wrap">
        <div class="rpd-block-head">
          <h3 class="rpd-block-title">{{ t('report.columns') }}</h3>
          <span class="rpd-hint">{{ t('report.columnsHint') }}</span>
        </div>

        <div v-if="previewErr" class="rpd-err">{{ previewErr }}</div>

        <!--
          刻意**不设 height**：表格按内容撑开。
          列配置通常只有 3~15 行，给它固定半屏纯属浪费（而且改到靠后的列还得先滚过去）；
          行数真的多起来时由外层 .rpd-cols-body 的 max-height 兜住再内部滚动。

          row-key 必须给：colDraft 每次预览都会被 mergeColumns 整体替换成新数组，
          没有 row-key 时 el-table 按行索引认行 → 整表重建 → 用户正在输入的框被清空。
        -->
        <div class="rpd-cols-body" v-loading="previewing">
          <el-empty
            v-if="!colDraft.length"
            :description="t('report.noColumns')"
            :image-size="72"
          />
          <el-table
            v-else
            :data="colDraft"
            row-key="key"
            border
            stripe
            size="small"
          >
              <el-table-column prop="key" :label="t('report.col.key')" min-width="170" />
              <el-table-column :label="t('report.col.label')" min-width="200">
                <template #default="{ row }">
                  <el-input v-model="row.label" size="small" />
                </template>
              </el-table-column>
              <el-table-column :label="t('report.col.width')" width="140" align="center">
                <template #default="{ row }">
                  <el-input-number
                    v-model="row.width"
                    :min="80"
                    :max="600"
                    :step="20"
                    size="small"
                    controls-position="right"
                    class="rpd-cell-input"
                  />
                </template>
              </el-table-column>
              <el-table-column :label="t('report.col.align')" width="120" align="center">
                <template #default="{ row }">
                  <el-select v-model="row.align" size="small" class="rpd-cell-input">
                    <el-option value="left" :label="t('report.alignLeft')" />
                    <el-option value="center" :label="t('report.alignCenter')" />
                    <el-option value="right" :label="t('report.alignRight')" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column :label="t('report.col.fixed')" width="90" align="center">
                <template #default="{ row }">
                  <el-checkbox v-model="row.fixed" />
                </template>
              </el-table-column>
          </el-table>
        </div>
      </div>
    </div>

    <!--
      数据预览：**内容区下方的浮窗**，点「预览数据」后才出现。

      为什么不用 el-drawer：抽屉是全视口宽度，会把左侧目录栏一起盖住 ——
      而浮窗的意义正是「一边看数据、一边回头改列配置」，盖掉目录栏就失去了这个前提。
      抽屉的遮罩（modal）同样会吃掉整页交互，改成普通的绝对定位浮层。

      为什么不用常驻区块：数据是**试跑的产物**不是配置。设计时反复要看的是
      「参数 + 控件 + 列配置」，数据只是验证手段，常驻一块吃掉近半屏会把配置区挤到要滚动。

      列直接复用 colDraft：改列名 / 列宽 / 对齐，这里立刻反映，所见即将播放页所得。
    -->
    <div v-if="previewDrawer" class="rpd-preview-float">
      <div class="rpd-preview-head">
        <span class="rpd-preview-title">{{ t('report.previewData') }}</span>
        <span class="rpd-hint">{{ t('report.previewDataHint') }}</span>
        <el-button link :icon="Close" @click="previewDrawer = false" />
      </div>
      <div class="rpd-preview-body" v-loading="previewing">
        <el-empty
          v-if="!previewRows.length"
          :description="t('report.previewNoData')"
          :image-size="72"
        />
        <el-table v-else :data="previewRows" border stripe size="small" height="100%">
          <el-table-column
            v-for="(c, ci) in colDraft"
            :key="c.key"
            :label="c.label || c.key"
            :width="c.width"
            :align="c.align"
          >
            <template #default="{ row }">{{ cellText(row[ci]) }}</template>
          </el-table-column>
        </el-table>
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
  /* 数据预览浮窗的定位基准：absolute 挂在这里，天生被内容区框住，不越界盖住侧边栏 */
  position: relative;
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
 * 顶部通栏：基本设置 + 护栏 + 查询条件合成的一块。
 *
 * 每项按**内容实际需要的宽度**给 flex-basis，不是一律平分：
 * 编码/名称/数字框本来就窄，描述才需要吃掉剩余空间。
 * 之前用 grid auto-fit 平分，「数据源查询」的下拉被拉到 1100px、
 * 「每页行数」只有 50px —— 宽度和内容长度彻底脱节。
 *
 * flex-wrap 保证窗口变窄时自动折行，不用再写媒体查询。
 */
.rpd-meta {
  flex: 0 0 auto;
  margin-top: 16px;
}

.rpd-meta-row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 0 20px;
}

.rpd-meta-row :deep(.el-form-item) {
  margin-bottom: 10px;
}

.rpd-fw-code {
  flex: 0 1 230px;
}
.rpd-fw-name {
  flex: 0 1 290px;
}
/* 查询名可能比较长，下拉给足宽度 */
.rpd-fw-query {
  flex: 0 1 380px;
}
/* 描述是唯一「有多少给多少」的字段 */
.rpd-fw-desc {
  flex: 1 1 320px;
}
/* 两个数字框定宽就够，值都是 2~5 位数 */
.rpd-fw-num {
  flex: 0 0 180px;
}

/* 护栏说明跟在数字框后面横排，不另起一行 */
.rpd-meta-hint {
  flex: 1 1 auto;
  align-self: center;
  padding-bottom: 10px;
}



/*
 * 中部并列：查询头 | 列配置。
 *
 * 1fr : 1.4fr —— 查询头从原来的 340px 宽裕到近 500px（一行里
 *   勾选 + 参数名 + 96px 控件下拉 + 恢复默认 + 上下移 终于不用省略号）；
 * 列配置那张表五列固定宽合计约 720px，多分一点才不至于横向滚动。
 * minmax 下限是保险：窗口再窄也别把任一面板压到不可用。
 */
.rpd-grid {
  flex: 1 1 auto;
  min-height: 260px;
  display: grid;
  grid-template-columns: minmax(320px, 1fr) minmax(460px, 1.4fr);
  gap: 16px;
  margin-top: 16px;
}

/* 查询头面板：内部纵向滚动，参数多时不会把整个页面撑长 */
.rpd-head-panel {
  min-height: 0;
  overflow: auto;
}

.rpd-block {
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-bg-elev);
  padding: 14px 16px;
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
  margin-bottom: 12px;
}

/*
 * 查询头面板的顶部行：标题在左，「每行字段数 + 查询按钮」在右。
 * 查询按钮放这儿是因为它操作的就是下面这些框，离得近才不会点错。
 */
.rpd-head-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 4px;
}

.rpd-block-title-inline {
  margin: 0;
}

.rpd-head-ops {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rpd-head-cols {
  width: 96px;
}

.rpd-head-cols-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  white-space: nowrap;
}

/* 默认值输入框：跟着参数名走，宽度给到够填一个常见值即可 */
.rpd-param-val {
  width: 150px;
  flex: 0 0 150px;
}

.rpd-param {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

/* 一行 = 勾选 + 参数名 + 控件类型 + 恢复默认 + 上下移 */
.rpd-param-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

/*
 * 控件类型下拉：固定宽度，且不允许被 flex 压扁（压扁的下拉框没法用）。
 * 96px 是「日期时间」四个字 + 箭头还能放下、再窄就开始截断的下限。
 */
.rpd-param-ctrl {
  width: 96px;
  flex: 0 0 96px;
}

/* 勾选框也允许收缩：标签过长时省略，而不是把整行撑破 */
.rpd-param-row :deep(.el-checkbox) {
  min-width: 0;
  flex: 0 1 auto;
  overflow: hidden;
}

.rpd-param-row :deep(.el-checkbox__label) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 候选值编辑器：缩进对齐到参数名，视觉上从属于上面那一行 */
.rpd-param-opts {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-left: 24px;
}

.rpd-warn {
  font-size: 12px;
  color: var(--color-warning);
  line-height: 1.5;
}

.rpd-param-name {
  font-size: 12px;
  color: var(--color-text-muted);
  flex: 1 1 auto;
  /* flex 子项默认 min-width:auto，不加这行就压不下去，会把整行撑破 */
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/*
 * 列配置（.rpd-grid 的第二个子项，父级是 grid 不是 flex）。
 *
 * align-self: start 让面板**贴着内容高度**而不是被 grid 拉满 ——
 * 它通常只有几行，拉满只会在表格下面留一大片空白边框。
 * 行数多起来时由 .rpd-cols-body 的 max-height 兜住再内部滚动，而且必须内部滚：
 * 这是个**编辑面**，要滚到一半才能改到靠后的列很难受。
 */
.rpd-cols-wrap {
  align-self: start;
  min-height: 160px;
  display: flex;
  flex-direction: column;
}

/*
 * 数据预览浮窗。
 *
 * absolute（不是 fixed、不是 sticky）是有意选的：
 * - fixed 铺满视口，会**盖住左侧目录栏** —— 而浮窗的前提恰恰是「目录栏还能用，
 *   一边看数据一边回头改列配置」；
 * - sticky 需要占流位置，一打开就把页面顶高一大截、还得滚动才看得到它；
 * - absolute 挂在 .rpd-page（position: relative）里，天生被内容区框住，
 *   高度用 vh 相对视口，也不会跟着内容一起长高。
 *
 * 阴影是必须的：不加投影它就贴着页面底边，看着像「页面被裁掉一块」而不是浮在上面。
 */
.rpd-preview-float {
  position: absolute;
  left: 16px;
  right: 16px;
  bottom: 16px;
  height: 50vh;
  z-index: 30;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-bg-elev);
  box-shadow: 0 -6px 28px rgba(15, 23, 42, 0.18);
}

.rpd-preview-head {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--color-border-soft);
}

.rpd-preview-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text);
}

.rpd-preview-head .rpd-hint {
  flex: 1 1 auto;
}

/* 表格撑满浮窗剩余高度；el-table 用 height="100%"，这里必须是确定高度 */
.rpd-preview-body {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* 表格内的数字输入 / 下拉铺满单元格，避免 EP 默认宽度把列撑得很难看 */
.rpd-cell-input {
  width: 100%;
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
</style>
