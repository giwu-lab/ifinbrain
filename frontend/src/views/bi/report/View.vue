<script setup lang="ts">
/**
 * 报表播放页：查询头 + 分页表格。
 *
 * 加载模型是**一页一页**的：offset = (page-1) × size，翻页替换而不是追加。
 * DOM 里任何时刻只有一页的行，所以「大结果集爆内存」这个老问题不成立 ——
 * 内存上界由 pageSize 决定，与结果集总行数无关。
 *
 * 换查询头参数 = 重新查询：回到第 1 页、按新参数取第一页。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { ArrowDown, ArrowUp, Refresh, Search } from '@element-plus/icons-vue';
import { reportApi, type Report, type ReportColumn, type ReportParamSchema } from '@/api/report';
import { firstMissingRequired, initialParams, mergeColumns, visibleParams } from '@/utils/report/header';
import QueryHeader from '@/components/report/QueryHeader.vue';
import CellText from '@/components/CellText.vue';
import { useTabStore } from '@/stores/tab';

const { t } = useI18n();
const route = useRoute();
const tabStore = useTabStore();

const reportId = computed(() => Number(route.params.id));
const report = ref<Report | null>(null);
const exporting = ref(false);
/** 该报表绑定的查询的参数 schema —— 运行时从 query 读，报表不冗余存 */
const paramSchema = ref<ReportParamSchema[]>([]);

const values = ref<Record<string, string>>({});
const rows = ref<any[][]>([]);
const resultColumns = ref<ReportColumn[]>([]);
const hasMore = ref(false);
const capped = ref(false);
const loading = ref(false);
const error = ref('');

/** 分页状态。offset 不直接存 —— 用 (page-1) × size 换算，换每页条数时不用重算偏移 */
const page = ref(1);
const pageSize = ref(50);
/** 后端 count 失败时是 -1（未知），分页器退化成 hasMore 驱动 */
const total = ref(-1);

const collapsed = ref(false);

/**
 * 参数多于 4 个才显示展开/收起 —— 阈值与 QueryHeader 内部折叠前 4 个保持一致。
 * 这里自己算而不用组件 ref：ref 只能拿到实例上的普通属性，
 * 读不到组件内部的 computed，写出来是「看起来联动、实际不更新」的假联动。
 */
const COLLAPSE_AT = 4;
const collapsible = computed(() => shownParams.value.length > COLLAPSE_AT);

/** 报表配置的每页行数，只作首次加载的默认值；用户临时改了以本地为准 */
const maxRows = computed(() => report.value?.maxRows ?? 50000);

// ── 表格高度：跟着「视口剩余空间」走，而不是写死 640 ────────────────────
// 写死 640 的后果：50 条/页时表被钉死，第 18 行被切成半行，
// 而 640 + 工具栏 + 分页只有 ~740px，视口有 1080px，下方空一大片。
//
// 为什么不走 flex 撑满：.main-inner 只有 min-height:100%（不是确定高度），
// 子元素 height:100% 会退回 auto，flex:1 1 0 把空间全吃掉并把工具栏挤成 0。
// 所以改成 JS 实测 —— 表格可用高度 = 视口高 - 表格区距视口顶 - 分页及间距。
//
// 行数不够撑满时 el-table 自己收缩到内容高度，所以「小页码不拖空边框」
// 不用额外处理：max-height 只是上限。
const wrapRef = ref<HTMLElement | null>(null);
const tableMaxHeight = ref(640);

/** 低于这个高度表格就没法看了（表头 + 约 4 行），兜底别让 flex 把它压扁 */
const MIN_TABLE_H = 200;

/**
 * 每页条数选项 —— 与项目其余 13 处分页保持一致，不要单独自成一档。
 */
const PAGE_SIZES = [10, 20, 50, 100];

/**
 * 设计器里每页行数是 el-input-number（20~2000 任意值），而这里只给上面 4 个选项。
 * 配置成 200 之类的报表直接透传，下拉会显示一个**列表里选不中的裸值**，
 * 且用户没法再选回那个值。取不小于配置值的最小选项兜底，超界则取最大档。
 */
function snapPageSize(n: number): number {
  if (!Number.isFinite(n) || n <= 0) return PAGE_SIZES[2];
  return PAGE_SIZES.find((s) => s >= n) ?? PAGE_SIZES[PAGE_SIZES.length - 1];
}

function recalcTableHeight() {
  const wrap = wrapRef.value;
  if (!wrap) return;

  // 直接量表格自身的顶端（document 坐标）—— 工具栏高度自然含在内，
  // 不用自己把 toolbar / error 条 / 各层 padding 加总一遍（那是复算，容易漏）
  const tableEl = wrap.querySelector('.el-table') as HTMLElement | null;
  const anchor = tableEl ?? wrap;
  const tableTop = anchor.getBoundingClientRect().top + window.scrollY;

  // 表格下方要留给：分页条（含自身 margin）+ wrap 下内边距 + page-container 下内边距。
  // 全部实测，不用魔数 —— 写死「减 24px」那种余量在工具栏展开/收起时会算错，
  // 表现为页面多出一条竖滚动条。
  let below: number;
  const pager = wrap.querySelector('.el-pagination') as HTMLElement | null;
  if (pager) {
    const ps = getComputedStyle(pager);
    below =
      pager.offsetHeight +
      (parseFloat(ps.marginTop) || 0) +
      (parseFloat(ps.marginBottom) || 0);
  } else {
    below = 48;
  }
  below += parseFloat(getComputedStyle(wrap).paddingBottom) || 0;
  const pageEl = wrap.parentElement;
  if (pageEl) below += parseFloat(getComputedStyle(pageEl).paddingBottom) || 0;

  tableMaxHeight.value = Math.max(MIN_TABLE_H, Math.floor(window.innerHeight - tableTop - below));
}

/** 有效列 = 结果列 ∩ 报表列配置 */
const tableColumns = computed(() => mergeColumns(resultColumns.value, report.value?.columns));

/** total 未知（后端 count 失败）时的总页数估计：已知行数 + 还有下一页就多算一页 */
const effectiveTotal = computed(() => {
  if (total.value >= 0) return total.value;
  const known = (page.value - 1) * pageSize.value + rows.value.length;
  return hasMore.value ? known + 1 : known;
});

const shownParams = computed(() =>
  visibleParams(paramSchema.value, report.value?.header)
);

async function loadReport() {
  loading.value = true;
  error.value = '';
  try {
    const r = await reportApi.detail(reportId.value);
    report.value = r;
    /*
     * 回填 tab / 面包屑标题 = 报表名。
     * 路由 meta 里的 `menu.bi.report.View`（报表展示）是模块级静态文案，
     * 一个路由对应 N 张报表时它分不清是哪张 —— 用户要看到的是「订单报表」。
     */
    tabStore.setTitle(`/bi/report/view/${reportId.value}`, r.name);
    // 参数 schema 存在绑定的查询上，不在报表里 —— 走 query-options 拿
    const opts = await reportApi.queryOptions();
    const opt = opts.find((o) => o.id === r.queryId);
    paramSchema.value = opt?.params ?? [];
    values.value = initialParams(paramSchema.value, r.defaultParams);
    collapsed.value = r.header?.collapsed ?? false;
    pageSize.value = snapPageSize(r.pageSize ?? 50);
    await query();
  } catch (e: any) {
    error.value = e?.message || t('report.loadFailed');
  } finally {
    loading.value = false;
  }
}

/**
 * 取一页。first=true 时顺便要总数（翻页复用已有 total，不必每页都付 COUNT 的钱）。
 */
async function fetchPage(first = false) {
  const miss = firstMissingRequired(shownParams.value, values.value);
  if (miss) {
    error.value = t('report.missingParam', {
      name: shownParams.value.find((p) => p.name === miss)?.label || miss,
    });
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    const resp = await reportApi.run(reportId.value, {
      params: values.value,
      offset: (page.value - 1) * pageSize.value,
      size: pageSize.value,
      withTotal: first,
    });
    rows.value = resp.rows ?? [];
    hasMore.value = resp.hasMore;
    capped.value = resp.capped;
    if (first) total.value = resp.total ?? -1;
    // 列只在首屏返回，后续页为省带宽是空数组。
    // 必须「非空才覆盖」—— 写成 `if (!len) columns = resp.columns` 会把已有列清成空数组，
    // 表格随即一行都渲染不出来（表现为「一直在加载但没有数据」）。
    if (resp.columns?.length) resultColumns.value = resp.columns;
  } catch (e: any) {
    error.value = e?.message || t('report.queryFailed');
  } finally {
    loading.value = false;
  }
}

/** 点「查询」：回到第 1 页重来（换条件后还停在第 7 页是最常见的困惑来源） */
async function query() {
  page.value = 1;
  await fetchPage(true);
}

/** 翻页：只换 offset，total 复用 */
async function onPageChange() {
  await fetchPage(false);
}

/**
 * 取单元格文本。
 *
 * <p>行是 `any[]`（按 columns 顺序的位置数组），不是对象，所以按列的下标取：
 * `resultColumns` 与 `tableColumns` 同源同序（mergeColumns 只做过滤与改标签，不重排）。
 * 找不到就退化成 String(row)，宁可显示原始值也不要空白格。
 */
function cellText(row: any[], key: string): string {
  const i = resultColumns.value.findIndex((c) => c.key === key);
  const v = i >= 0 ? row[i] : row;
  if (v === null || v === undefined || v === '') return '';
  return typeof v === 'object' ? JSON.stringify(v) : String(v);
}

/**
 * 切每页条数必须回到第 1 页：停在第 7 页改 size，offset = 6 × 新 size 可能已越过结果末尾，
 * 页面会停在一个空页上。
 */
async function onSizeChange() {
  page.value = 1;
  await fetchPage(true);
}

/**
 * 按**当前查询头条件**导出完整结果（受报表 maxRows 护栏约束）。
 *
 * 与列表页的导出区别就在这里：列表页只能拿报表默认参数，播放页是用户填完条件
 * 再导 —— 这才是报表导出真正有用的形态。
 */
async function onExport(kind: 'csv' | 'xlsx') {
  if (exporting.value) return;
  exporting.value = true;
  try {
    await reportApi.exportFile(reportId.value, kind, values.value);
    ElMessage.success(t('report.exportOk', { kind: kind.toUpperCase() }));
  } catch (e: unknown) {
    ElMessage.error(t('report.exportFail') + ': ' + (e instanceof Error ? e.message : String(e)));
  } finally {
    exporting.value = false;
  }
}

onMounted(async () => {
  window.addEventListener('resize', recalcTableHeight);
  await loadReport();
  // 必须等数据回来再量：工具栏是 v-if="shownParams.length"，
  // 数据没到之前 wrap 的 docTop 是旧的，算出来的可用高度偏大 → 页面反而多出滚动条
  nextTick(recalcTableHeight);
});

onBeforeUnmount(() => window.removeEventListener('resize', recalcTableHeight));

watch(reportId, loadReport);

// 每页条数变了要重量：行数变影响是否需要内部滚动，工具栏也可能跟着变高
watch(pageSize, () => nextTick(recalcTableHeight));
// 展开/收起查询头直接改变表格区起点，DOM 更新完再量，否则量到旧高度
watch(collapsed, () => nextTick(recalcTableHeight));
</script>

<template>
  <div class="page-container fade-in-up rpv-page">
    <div ref="wrapRef" class="table-wrap rpv-table-wrap">
      <!--
        查询区与发票列表同一套骨架：左侧 .field 横排字段（label 在左），
        右侧 .toolbar-right 收「统计 + 展开收起 + 查询 + 导出」。
        .toolbar / .field / .label 的样式在 global.css，页面不重复写。
      -->
      <div v-if="shownParams.length" class="toolbar">
        <QueryHeader
          v-model="values"
          :params="shownParams"
          :fields="report?.header?.fields"
          :collapsed="collapsed"
          :loading="loading"
          @search="query"
        />
        <div class="toolbar-right">
          <span v-if="report" class="toolbar-stat tabular">
            {{ total >= 0 ? t('report.totalRows', { n: total }) : t('report.loaded', { n: rows.length }) }}
          </span>
          <span v-if="capped" class="toolbar-cap tabular">{{ t('report.capped', { n: maxRows }) }}</span>
          <el-button v-if="collapsible" link :icon="collapsed ? ArrowDown : ArrowUp" @click="collapsed = !collapsed">
            {{ collapsed ? t('report.expand') : t('report.collapse') }}
          </el-button>
          <el-button type="primary" :icon="Search" :loading="loading" @click="query">
            {{ t('report.query') }}
          </el-button>
          <el-dropdown split-button type="success" :loading="exporting" @click="onExport('csv')" @command="onExport">
            {{ t('report.exportCsv') }}
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="csv">{{ t('report.exportCsv') }}</el-dropdown-item>
                <el-dropdown-item command="xlsx">{{ t('report.exportXlsx') }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>

      <div v-if="error" class="rpv-error">
        <span>{{ error }}</span>
        <el-button link type="primary" :icon="Refresh" @click="query">{{ t('report.retry') }}</el-button>
      </div>

      <!--
        表格与发票列表同款：border stripe size="small" + max-height + 容器内 el-pagination。
        「单元格单行 + 省略号」由 global.css 全局生效，这里不用写。

        max-height 不是常量，而是 recalcTableHeight() 实测的「视口剩余高度」：
        撑满 → 页下方不留白；行数不够时 el-table 自动收缩 → 不拖空边框。
        换每页条数 / 展开收起查询头 / 改窗口大小都会重量。

        不用虚拟滚动：页码分页下 DOM 里任何时刻只有一页（pageSize 行），
        内存上界由每页条数决定，与结果集总行数无关 —— 这是当初上虚拟滚动要解决的问题，
        分页用更小的代码把它解决了。真正的全量取数走导出（iterateRows 流式）。
      -->
      <el-table
        :data="rows"
        v-loading="loading"
        border
        stripe
        size="small"
        :max-height="tableMaxHeight"
      >
        <el-table-column
          v-for="c in tableColumns"
          :key="c.key"
          :prop="c.key"
          :label="c.label"
          :width="c.width"
          :align="c.align"
          :fixed="c.fixed ? 'left' : undefined"
        >
          <template #default="{ row }">
            <!-- el-table 的 slot row 是 DefaultRow，这里断言成位置数组（列已由 :data 约束） -->
            <CellText :text="cellText(row as any[], c.key)" />
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="error || t('report.noData')" />
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :total="effectiveTotal"
        :page-sizes="PAGE_SIZES"
        layout="total,sizes,prev,pager,next,jumper"
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </div>
  </div>
</template>

<style scoped>
/*
 * 报表播放页：与发票列表完全同骨架 —— page-container + table-wrap + toolbar +
 * el-table(border stripe size="small") + 容器内 el-pagination。
 * 之前铺满视口高度的 flex 写法已删：.main-inner 只有 min-height:100%（不是确定高度），
 * 子元素 height:100% 会退回 auto，flex:1 1 0 把空间全吃掉而把工具栏挤成 0。
 */

/* 工具栏不参与收缩 —— 上面那个坑的直接教训，写死以防有人再套 flex */
.rpv-page .toolbar {
  flex-shrink: 0;
}

/* 工具栏右端的「共 N 条 / 已触顶」：小字 + 等宽，弱于按钮但要能一眼扫到 */
.toolbar-stat,
.toolbar-cap {
  font-size: var(--font-size-sm);
  color: var(--color-text-muted);
}
.toolbar-cap {
  color: var(--color-warning);
}

.rpv-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  margin-bottom: var(--space-6);
  border: 1px solid var(--color-danger);
  border-radius: 6px;
  background: var(--color-danger-soft);
  color: var(--color-danger);
  font-size: var(--font-size-sm);
}
</style>
