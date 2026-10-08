<script setup lang="ts">
/**
 * 虚拟滚动表格（报表专用）。
 *
 * ## 为什么不用 el-table
 *
 * el-table 会为**每一行**渲染一个真实 DOM 元素。1 万行 × 20 列 = 20 万个 DOM 节点，
 * 浏览器会先卡死再崩溃。虚拟滚动只渲染视口内的行，DOM 数量恒定在「可视行数 × 列数」，
 * 与总行数无关。
 *
 * ## 三层内存防线
 *
 * ① 后端分页（QueryExecutor.executePaged）：单次请求最多收 pageSize 行，
 *    底表多大都不影响单次堆占用。这是主力。
 * ② 本地有界缓存：只保留最近 MAX_CACHE 页的数据，超出从头淘汰。
 *    「无限滚动」如果不淘汰，滚一晚上还是会把内存吃光。
 * ③ 虚拟渲染：DOM 节点数与总行数解耦。
 *
 * ②③ 都不是必需的，但没有 ②，无界滚动仍然会爆；没有 ③，DOM 节点会拖垮渲染。
 * 三层叠加才有「大数据量下前端内存有界」这个结果。
 *
 * 淘汰策略用**从头截断**（keep last N）而不是环形缓冲：
 * 用户向上滚回去要重新拉一页（一次请求），但内存曲线永远单调下降，
 * 而环形缓冲会让「回到顶部」和「继续下拉」互相踩。报表的常见动作是「从上往下扫」，
 * 从头淘汰的命中率更好。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ElTableV2 } from 'element-plus';
import type { TableV2Column } from './types';
import { cellText } from '@/utils/report/header';

const props = withDefaults(defineProps<{
  /** 已加载的行（每行是 any[]，顺序对齐 columns） */
  rows: any[][];
  /** 列定义 */
  columns: TableV2Column[];
  /** 行高（px） */
  rowHeight?: number;
  /** 是否还有下一页 */
  hasMore: boolean;
  /** 是否正在取下一页 */
  loadingMore: boolean;
  /** 滚动接近底部时触发，由父组件去取数 */
  loadMore: () => void;
  /** 缓存上限（行数），超出从头淘汰 */
  maxCache?: number;
  /** 行高变化的回调，让父组件同步裁剪数据 */
  onTrim?: (keptRows: any[][], keptColumns: TableV2Column[]) => void;
}>(), {
  rowHeight: 36,
  maxCache: 20_000,
  onTrim: () => {},
});

const emit = defineEmits<{
  (e: 'row-click', row: any[], index: number): void;
}>();

/** el-table-v2 要求 width / height 是确定数值，不能是百分比 */
const bodyWidth = ref(600);
const bodyHeight = ref(400);

/** 表头高度，必须与模板里的 :header-height 一致（算数据区可视高度要用） */
const HEADER_H = 40;
/** 距底部多少 px 就该预取下一页 */
const SCROLL_THRESHOLD = 300;

const rootRef = ref<HTMLElement | null>(null);

/**
 * 已加载内容的总高度。
 *
 * el-table-v2 是**定高虚拟列表**（estimatedRowHeight 传空），内容高度就是行数 × 行高，
 * 由它自己算滚动条长度 —— DOM 里不存在这个高度，wrapper.scrollHeight 恒等于可视区高度。
 * 所以只能用这个公式推，量 DOM 拿不到。
 */
const contentHeight = computed(() => props.rows.length * props.rowHeight);

/** 能不能继续拉下一页（三个条件缺一不可） */
function canLoad(): boolean {
  if (!props.hasMore) return false;
  if (props.loadingMore) return false;
  if (props.rows.length <= 0) return false;
  return true;
}

function tryLoad() {
  if (canLoad()) props.loadMore();
}

/**
 * 滚动加载判定。
 *
 * 监听的 scroll 事件来自 el-table-v2 内部的 `.el-vl__wrapper`（它用 transform 定位可视行），
 * **不是**外层 .vt-wrap。scroll 不冒泡，但**捕获阶段**可以拿到子元素的 scroll ——
 * 所以监听必须写 `@scroll.capture`，`@scroll` 收不到。这是「表格只有首屏、
 * 再怎么滚都加载不出下一页」的直接原因。
 *
 * 判定用「行数 × 行高」推内容高度，而不是读 scrollHeight —— 后者恒等于可视区高度，
 * 会让条件恒真/恒假。
 */
function onScrollCapture(e: Event) {
  if (!canLoad()) return;
  const el = e.target as HTMLElement | null;
  if (!el || el === rootRef.value) return;
  const visible = el.clientHeight - HEADER_H;
  if (visible <= 0) return;
  const remain = contentHeight.value - (el.scrollTop + visible);
  if (remain <= SCROLL_THRESHOLD) tryLoad();
}

/**
 * 可视区没被填满时自动补页。
 *
 * 场景：屏幕很高 / pageSize 很小，一页 50 行放不满一屏 → 没有滚动条 → 用户滚不动 →
 * 加载永远不会触发，表现为「只看到 50 行，后面没了」。这里主动补到填满或取尽为止。
 */
function autoFill() {
  if (!canLoad()) return;
  if (contentHeight.value + HEADER_H <= bodyHeight.value) tryLoad();
}

/** 列去重成 table-v2 的 Column[] */
const v2Columns = computed<any[]>(() => {
  return props.columns.map((c) => ({
    key: c.key,
    dataKey: c.key,
    title: c.label,
    width: c.width,
    fixed: c.fixed,
    align: c.align,
    sortable: false,
    headerCellClass: 'vt-header-cell',
    cellClass: c.align === 'right' ? 'vt-cell vt-cell-num' : 'vt-cell',
    cellData: undefined,
  }));
});

/** 行数据包一层，给每行一个稳定 key（table-v2 需要） */
const v2Data = computed<any[]>(() => {
  const cols = props.columns;
  return props.rows.map((row, i) => {
    const obj: Record<string, any> = { __idx: i };
    for (let c = 0; c < cols.length; c++) {
      obj[cols[c].key] = cellText(row[c]);
    }
    return obj;
  });
});

function onRowClick({ rowData }: any) {
  const i = rowData?.__idx;
  if (typeof i === 'number' && props.rows[i]) emit('row-click', props.rows[i], i);
}

/**
 * 尺寸测量。
 *
 * el-table-v2 的可视高度来自 height prop，滚动条长度由「行数 × 行高」自己算。
 * height 一旦算错就表现为「表格挤在一小框里 / 滚动条长度不对」，而且不会自愈 ——
 * 父容器尺寸没再变，ResizeObserver 也不会再触发。
 *
 * ① 量到 0 不用（保留旧值），等布局稳定后重测；
 * ② 行数 / 尺寸变化后都补测一次，覆盖「首屏测量早于 flex 布局完成」的时序。
 */
function measure() {
  const el = rootRef.value;
  if (!el) return;
  const w = el.clientWidth;
  const h = el.clientHeight;
  if (w > 0) bodyWidth.value = Math.max(200, w);
  if (h > 0) bodyHeight.value = Math.max(160, h);
}

let ro: ResizeObserver | null = null;
onMounted(() => {
  const kick = () => {
    measure();
    autoFill();
  };
  nextTick(() => {
    kick();
    requestAnimationFrame(kick);
    // 再补一拍：等父容器 flex:1 布局完成
    setTimeout(kick, 60);
    if (rootRef.value && typeof ResizeObserver !== 'undefined') {
      ro = new ResizeObserver(() => {
        measure();
        autoFill();
      });
      ro.observe(rootRef.value);
    }
  });
});

onBeforeUnmount(() => {
  if (ro) { ro.disconnect(); ro = null; }
});

/** 数据到达后补测一次，并检查要不要继续补页 */
watch(
  () => props.rows.length,
  () => {
    nextTick(() => {
      measure();
      autoFill();
    });
  }
);

/** 行数超过缓存上限 → 从头裁剪，并通知父组件同步裁剪它的 rows（否则父子内存不同步） */
watch(
  () => props.rows.length,
  (n) => {
    if (n <= props.maxCache) return;
    const keep = props.rows.slice(n - props.maxCache);
    props.onTrim(keep, props.columns);
  }
);
</script>

<template>
  <div ref="rootRef" class="vt-wrap" @scroll.capture="onScrollCapture">
    <ElTableV2
      :columns="v2Columns"
      :data="v2Data"
      :width="bodyWidth"
      :height="bodyHeight"
      :row-height="rowHeight"
      :header-height="HEADER_H"
      :row-event-handlers="{ onClick: onRowClick }"
    >
      <template #cell="{ column, rowData }">
        <span class="vt-text">{{ (column.dataKey ? rowData[column.dataKey] : '') }}</span>
      </template>
    </ElTableV2>

    <!-- 加载态：固定在底部状态条，不遮挡表格 -->
    <div class="vt-foot">
      <span v-if="loadingMore" class="vt-loading">
        <span class="vt-spinner" aria-hidden="true"></span>
        {{ $t('report.loadingMore') }}
      </span>
      <span v-else-if="!hasMore && rows.length" class="vt-end">{{ $t('report.allLoaded') }}</span>
      <span v-else-if="!rows.length" class="vt-end">{{ $t('report.noData') }}</span>
    </div>
  </div>
</template>

<style scoped>
.vt-wrap {
  position: relative;
  width: 100%;
  height: 100%;
  /* 滚动由 el-table-v2 内部的 .el-vl__wrapper 负责。
     这里必须 overflow:hidden，否则会出现「外层也滚」的双滚动条，
     用户在外层拖到底却永远触发不到虚拟列表的加载判定。 */
  overflow: hidden;
  background: var(--color-bg-elev);
}

.vt-text {
  display: block;
  padding: 0 10px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 34px;
  font-size: var(--font-size-sm);
}

.vt-foot {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 28px;
  pointer-events: none;
  background: var(--color-bg-elev);
  border-top: 1px solid var(--color-border-soft);
  font-size: var(--font-size-sm);
  color: var(--color-text-muted);
}

.vt-loading {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--color-text-secondary);
}

.vt-spinner {
  width: 13px;
  height: 13px;
  border: 2px solid var(--color-border-strong);
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: vt-spin 0.7s linear infinite;
}

@keyframes vt-spin {
  to { transform: rotate(360deg); }
}
</style>
