<script setup lang="ts">
/**
 * 洞察 tab：时间趋势 + 象限结构 + 行动建议。
 *
 * <p><b>为什么不用 ECharts / Chart.js</b>：项目没有图表依赖，而这里的图只有两种
 * （双折线 + 横向堆叠条），为两张图引入几百 KB 的库不划算。纯 SVG 的另一个好处是
 * 颜色全部走 CSS 变量，切暗色自动跟随 —— 图表库的配色要额外配主题。
 *
 * <p>数字全部来自后端 {@code /todo/tasks/insight}，前端不做二次口径计算 ——
 * 统计口径只有一处定义，才不会出现「洞察说逾期 5、四象限显示 6」这类自相矛盾。
 */
import { ref, computed, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  WarningFilled, TrendCharts, Delete, Share, Calendar, CircleCheck, InfoFilled,
} from '@element-plus/icons-vue';
import {
  todoApi, type TodoInsightResponse, type TodoSuggestion, type TodoQuadrantShare,
} from '@/api/todo';

const emit = defineEmits<{ (e: 'range-change'): void }>();

const { t } = useI18n();

const days = ref<7 | 30>(7);
const loading = ref(false);
const data = ref<TodoInsightResponse | null>(null);

const RANGES: { value: 7 | 30; labelKey: string }[] = [
  { value: 7, labelKey: 'todo.insight.days7' },
  { value: 30, labelKey: 'todo.insight.days30' },
];

const QUAD_META: Record<string, { nameKey: string; icon: typeof WarningFilled }> = {
  Q1: { nameKey: 'todo.quad.Q1.name', icon: WarningFilled },
  Q2: { nameKey: 'todo.quad.Q2.name', icon: Calendar },
  Q3: { nameKey: 'todo.quad.Q3.name', icon: Share },
  Q4: { nameKey: 'todo.quad.Q4.name', icon: Delete },
};

async function load() {
  loading.value = true;
  try {
    data.value = await todoApi.insight(days.value);
  } catch {
    /* 拦截器已弹错 */
  } finally {
    loading.value = false;
  }
}

watch(days, () => {
  load();
  emit('range-change');
});
load();

// ================= 指标卡 =================

interface Metric {
  key: string;
  labelKey: string;
  value: string;
  /** 可选的进度条比例 0~1 */
  ratio: number | null;
  tone: 'default' | 'good' | 'warn' | 'bad';
}

function fmtHours(h: number | null): string {
  if (h === null || !Number.isFinite(h)) return '—';
  if (h < 1) return t('todo.insight.minutes', { n: Math.round(h * 60) });
  if (h < 48) return t('todo.insight.hours', { n: Math.round(h) });
  return t('todo.insight.days', { n: Math.round(h / 24) });
}

const metrics = computed<Metric[]>(() => {
  const d = data.value;
  if (!d) return [];
  const pct = (v: number) => `${Math.round(v * 100)}%`;
  return [
    {
      key: 'completionRate',
      labelKey: 'todo.insight.completionRate',
      value: pct(d.completionRate),
      ratio: d.completionRate,
      tone: d.completionRate >= 0.6 ? 'good' : d.completionRate >= 0.3 ? 'default' : 'bad',
    },
    {
      key: 'overdueRate',
      labelKey: 'todo.insight.overdueRate',
      value: pct(d.overdueRate),
      ratio: d.overdueRate,
      tone: d.overdueRate === 0 ? 'good' : d.overdueRate < 0.2 ? 'default' : 'bad',
    },
    {
      key: 'avgCycle',
      labelKey: 'todo.insight.avgCycle',
      value: fmtHours(d.avgCycleHours),
      ratio: null,
      tone: 'default',
    },
    {
      key: 'backlog',
      labelKey: 'todo.insight.backlog',
      value: String(d.backlog),
      ratio: null,
      tone: d.backlog > 15 ? 'bad' : d.backlog > 8 ? 'warn' : 'good',
    },
    {
      key: 'estimate',
      labelKey: 'todo.insight.totalEstimate',
      value: fmtHours(d.totalEstimateHours),
      ratio: d.estimatedCoverage,
      tone: 'default',
    },
    {
      key: 'net',
      labelKey: 'todo.insight.netFlow',
      value: (d.createdInRange - d.completedInRange > 0 ? '+' : '') + (d.createdInRange - d.completedInRange),
      ratio: null,
      tone: d.createdInRange > d.completedInRange ? 'warn' : 'good',
    },
  ];
});

// ================= 趋势折线 =================

/** SVG 视口坐标系。留出上下左右 padding 给文字标签。 */
const CHART_W = 640;
const CHART_H = 160;
const PAD_L = 30;
const PAD_R = 12;
const PAD_T = 12;
const PAD_B = 22;

const chart = computed(() => {
  const d = data.value;
  if (!d || d.trend.length === 0) return null;
  const points = d.trend;
  const maxVal = Math.max(1, ...points.map((p) => Math.max(p.completed, p.created)));
  // y 轴向上取整到「好看」的刻度，避免出现 3.7 这种上限
  const step = maxVal <= 5 ? 1 : maxVal <= 10 ? 2 : maxVal <= 20 ? 5 : 10;
  const yMax = Math.ceil(maxVal / step) * step;
  const innerW = CHART_W - PAD_L - PAD_R;
  const innerH = CHART_H - PAD_T - PAD_B;
  const x = (i: number) => PAD_L + (points.length === 1 ? innerW / 2 : (innerW * i) / (points.length - 1));
  const y = (v: number) => PAD_T + innerH - (innerH * v) / yMax;

  const line = (key: 'completed' | 'created') =>
    points.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(p[key]).toFixed(1)}`).join(' ');

  // 面积填充：只给「完成」线加，从强调重点
  const area = points.length > 1
    ? `M${x(0).toFixed(1)},${(PAD_T + innerH).toFixed(1)} ` +
      points.map((p, i) => `L${x(i).toFixed(1)},${y(p.completed).toFixed(1)}`).join(' ') +
      ` L${x(points.length - 1).toFixed(1)},${(PAD_T + innerH).toFixed(1)} Z`
    : '';

  // x 轴标签：7 天全标，30 天只标首/中/末 + 每 7 天一个，避免文字重叠
  const xLabels = points.map((p, i) => {
    const show = points.length <= 7 || i === 0 || i === points.length - 1 || i % 7 === 0;
    return { x: x(i), text: show ? p.date.slice(5) : '', y: y(p.completed), created: p.created, completed: p.completed };
  });

  const yTicks = [0, yMax / 2, yMax].map((v) => ({ y: y(v), text: String(Math.round(v)) }));

  return {
    lineCompleted: line('completed'),
    lineCreated: line('created'),
    area,
    dots: points.map((p, i) => ({ x: x(i), yCompleted: y(p.completed), yCreated: y(p.created), v: p })),
    xLabels,
    yTicks,
    hasData: points.some((p) => p.completed > 0 || p.created > 0),
  };
});

// ================= 象限结构 =================

const quadTotal = computed(() =>
  (data.value?.quadrants || []).reduce((s, q) => s + q.count, 0),
);

const quadRows = computed(() =>
  (data.value?.quadrants || []).map((q) => {
    const share = quadTotal.value === 0 ? 0 : q.count / quadTotal.value;
    return {
      ...q,
      share,
      sharePct: Math.round(share * 100),
      meta: QUAD_META[q.key],
    };
  }),
);

// ================= 建议 =================

const SUG_ICON: Record<string, typeof WarningFilled> = {
  overdue: WarningFilled,
  backlog: TrendCharts,
  q4: Delete,
  q3: Share,
  q2: Calendar,
  balanced: CircleCheck,
};

function sugIcon(s: TodoSuggestion) {
  return SUG_ICON[s.kind] || InfoFilled;
}

function fmtEstimate(min: number): string {
  if (!min) return '—';
  return fmtHours(min / 60);
}

defineExpose({ reload: load });
</script>

<template>
  <div v-loading="loading" class="insight">
    <!-- 时间窗口切换：7 / 30 天 -->
    <div class="insight-bar">
      <span class="insight-bar-label">{{ t('todo.insight.range') }}</span>
      <div class="insight-range">
        <button
          v-for="r in RANGES"
          :key="r.value"
          type="button"
          class="range-btn"
          :class="{ 'is-active': days === r.value }"
          @click="days = r.value"
        >{{ t(r.labelKey) }}</button>
      </div>
    </div>

    <template v-if="data">
      <!-- 指标卡：6 项，2 行 3 列 -->
      <div class="metric-grid">
        <div
          v-for="m in metrics"
          :key="m.key"
          class="metric"
          :class="`tone-${m.tone}`"
        >
          <div class="metric-label">{{ t(m.labelKey) }}</div>
          <div class="metric-value">{{ m.value }}</div>
          <div v-if="m.ratio !== null" class="metric-bar">
            <div class="metric-bar-fill" :style="{ width: Math.round(m.ratio * 100) + '%' }" />
          </div>
          <div v-else class="metric-hint">{{
            m.key === 'estimate' ? t('todo.insight.estimateCoverage', { n: Math.round(data.estimatedCoverage * 100) }) : ''
          }}</div>
        </div>
      </div>

      <div class="insight-cols">
        <!-- 趋势 -->
        <section class="panel">
          <header class="panel-head">
            <h3 class="panel-title">{{ t('todo.insight.trendTitle') }}</h3>
            <div class="legend">
              <span class="legend-item"><i class="dot dot-done" />{{ t('todo.insight.legendCompleted') }}</span>
              <span class="legend-item"><i class="dot dot-new" />{{ t('todo.insight.legendCreated') }}</span>
            </div>
          </header>

          <div v-if="!chart" class="empty">{{ t('todo.insight.empty') }}</div>
          <div v-else-if="!chart.hasData" class="empty">{{ t('todo.insight.noTrend') }}</div>
          <!-- 不设 preserveAspectRatio="none"：那会把折线按容器宽高比强行拉伸，
               圆点变椭圆、线宽失真。默认 meet 保持等比，容器窄时整体缩放。 -->
          <svg v-else class="chart" :viewBox="`0 0 ${CHART_W} ${CHART_H}`">
            <!-- y 轴刻度 -->
            <g class="grid">
              <line
                v-for="tk in chart.yTicks"
                :key="'y' + tk.text"
                :x1="PAD_L" :x2="CHART_W - PAD_R" :y1="tk.y" :y2="tk.y"
              />
            </g>
            <g class="axis-text">
              <text v-for="tk in chart.yTicks" :key="'yt' + tk.text" :x="PAD_L - 6" :y="tk.y + 3" text-anchor="end">
                {{ tk.text }}
              </text>
            </g>

            <path :d="chart.area" class="area-done" />
            <path :d="chart.lineCreated" class="line line-new" />
            <path :d="chart.lineCompleted" class="line line-done" />

            <g v-for="(p, i) in chart.dots" :key="i">
              <circle :cx="p.x" :cy="p.yCreated" r="2.5" class="pt pt-new" />
              <circle :cx="p.x" :cy="p.yCompleted" r="3" class="pt pt-done" />
            </g>

            <g class="axis-text">
              <text
                v-for="(lb, i) in chart.xLabels"
                :key="'x' + i"
                :x="lb.x" :y="CHART_H - 6"
                text-anchor="middle"
              >{{ lb.text }}</text>
            </g>
          </svg>
        </section>

        <!-- 象限结构 -->
        <section class="panel">
          <header class="panel-head">
            <h3 class="panel-title">{{ t('todo.insight.structureTitle') }}</h3>
            <span class="panel-sub muted">{{ t('todo.insight.structureSub', { n: quadTotal }) }}</span>
          </header>

          <div v-if="quadTotal === 0" class="empty">{{ t('todo.insight.noBacklog') }}</div>
          <ul v-else class="quad-list">
            <li v-for="q in quadRows" :key="q.key" class="quad-row">
              <div class="quad-head">
                <el-icon class="quad-icon" :class="q.key.toLowerCase()">
                  <component :is="q.meta.icon" />
                </el-icon>
                <span class="quad-name">{{ t(q.meta.nameKey) }}</span>
                <span class="quad-count tabular">{{ q.count }}</span>
                <span class="quad-est muted">{{ fmtEstimate(q.estimateMinutes) }}</span>
              </div>
              <div class="quad-bar">
                <div class="quad-bar-fill" :class="q.key.toLowerCase()" :style="{ width: q.sharePct + '%' }" />
              </div>
            </li>
          </ul>
        </section>
      </div>

      <!-- 行动建议 -->
      <section class="panel">
        <header class="panel-head">
          <h3 class="panel-title">{{ t('todo.insight.suggestTitle') }}</h3>
        </header>
        <ul class="sug-list">
          <li v-for="(s, i) in data.suggestions" :key="i" class="sug" :class="`kind-${s.kind}`">
            <el-icon class="sug-icon"><component :is="sugIcon(s)" /></el-icon>
            <div class="sug-body">
              <div class="sug-title">{{ s.title }}</div>
              <div class="sug-detail">{{ s.detail }}</div>
            </div>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>
.insight { padding-bottom: 8px; }

/* —— 时间窗口切换 —— */
.insight-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.insight-bar-label { font-size: 12px; color: var(--color-text-muted); }
.insight-range {
  display: inline-flex; gap: 2px; padding: 2px;
  background: var(--color-bg); border: 1px solid var(--color-border-soft); border-radius: 8px;
}
.range-btn {
  padding: 4px 12px; border: 0; border-radius: 6px; cursor: pointer;
  background: transparent; color: var(--color-text-secondary);
  font-size: 12px; font-family: inherit;
  transition: background .15s ease, color .15s ease;
}
.range-btn:hover { color: var(--color-text); }
.range-btn.is-active { background: var(--color-primary-soft); color: var(--color-primary); font-weight: 600; }

/* —— 指标卡 ——
   minmax(0, 1fr) 而非 1fr：grid 项默认 min-width:auto，「平均完成耗时」这类
   长标签会把轨道撑破，整行溢出到容器左侧被裁掉。0 表示允许收缩到内容以下。 */
.metric-grid {
  display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-bottom: 14px;
}
@media (max-width: 720px) { .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }

.metric {
  background: var(--color-bg-elev); border: 1px solid var(--color-border-soft);
  border-radius: 10px; padding: 10px 12px;
  /* 左侧竖条表达状态，不靠背景色块 —— 暗色下浅色块会刺眼 */
  border-left: 3px solid var(--color-border);
}
.metric.tone-good { border-left-color: var(--color-success); }
.metric.tone-default { border-left-color: var(--color-primary); }
.metric.tone-warn { border-left-color: var(--color-warning); }
.metric.tone-bad  { border-left-color: var(--color-danger); }

.metric-label { font-size: 11.5px; color: var(--color-text-muted); margin-bottom: 3px; }
.metric-value {
  font-size: 21px; font-weight: 700; color: var(--color-text);
  font-variant-numeric: tabular-nums; line-height: 1.15;
}
.metric-bar {
  margin-top: 7px; height: 3px; border-radius: 2px;
  background: var(--color-border-soft); overflow: hidden;
}
.metric-bar-fill { height: 100%; background: var(--color-primary); border-radius: 2px; }
.metric.tone-good .metric-bar-fill { background: var(--color-success); }
.metric.tone-bad  .metric-bar-fill { background: var(--color-danger); }
.metric-hint { margin-top: 6px; font-size: 11px; color: var(--color-text-muted); min-height: 14px; }

/* —— 双栏：趋势 + 结构 —— */
.insight-cols {
  display: grid; grid-template-columns: 1.35fr 1fr; gap: 12px; margin-bottom: 12px;
}
@media (max-width: 1000px) { .insight-cols { grid-template-columns: minmax(0, 1fr); } }

.panel {
  background: var(--color-bg-elev); border: 1px solid var(--color-border-soft);
  border-radius: 10px; padding: 12px 14px;
  min-width: 0; /* grid 子项默认不收缩，SVG 视口会把它撑宽 */
}
.panel-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 10px; }
.panel-title { margin: 0; font-size: 13px; font-weight: 600; color: var(--color-text); }
.panel-sub { font-size: 11.5px; }

.legend { display: flex; gap: 12px; }
.legend-item { display: inline-flex; align-items: center; gap: 5px; font-size: 11.5px; color: var(--color-text-secondary); }
.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.dot-done { background: var(--color-primary); }
.dot-new  { background: var(--color-text-muted); }

/* —— 趋势图 —— */
.chart { width: 100%; height: 170px; display: block; overflow: visible; }
.chart .grid line { stroke: var(--color-border-soft); stroke-width: 1; }
.chart .axis-text { fill: var(--color-text-muted); font-size: 10px; font-variant-numeric: tabular-nums; }
.chart .area-done { fill: var(--color-primary-soft); }
.chart .line { fill: none; stroke-width: 2; stroke-linejoin: round; stroke-linecap: round; }
.chart .line-done { stroke: var(--color-primary); }
.chart .line-new  { stroke: var(--color-text-muted); stroke-width: 1.5; stroke-dasharray: 4 3; }
.chart .pt { stroke: var(--color-bg-elev); stroke-width: 1.5; }
.chart .pt-done { fill: var(--color-primary); }
.chart .pt-new  { fill: var(--color-text-muted); }

/* —— 象限结构 —— */
.quad-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 9px; }
.quad-row { display: flex; flex-direction: column; gap: 4px; }
.quad-head { display: flex; align-items: center; gap: 7px; font-size: 12.5px; }
.quad-icon { font-size: 13px; }
.quad-icon.q1 { color: var(--color-danger); }
.quad-icon.q2 { color: var(--color-primary); }
.quad-icon.q3 { color: var(--color-warning); }
.quad-icon.q4 { color: var(--color-text-muted); }
.quad-name { color: var(--color-text-secondary); }
.quad-count { margin-left: auto; font-weight: 600; color: var(--color-text); }
.quad-est { font-size: 11.5px; min-width: 52px; text-align: right; }

.quad-bar { height: 5px; border-radius: 3px; background: var(--color-border-soft); overflow: hidden; }
.quad-bar-fill { height: 100%; border-radius: 3px; transition: width .3s ease; }
.quad-bar-fill.q1 { background: var(--color-danger); }
.quad-bar-fill.q2 { background: var(--color-primary); }
.quad-bar-fill.q3 { background: var(--color-warning); }
.quad-bar-fill.q4 { background: var(--color-text-muted); }

/* —— 建议 —— */
.sug-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.sug {
  display: flex; gap: 9px; align-items: flex-start;
  padding: 9px 11px; border-radius: 8px;
  background: var(--color-bg); border: 1px solid var(--color-border-soft);
  border-left: 3px solid var(--color-border);
}
.sug.kind-overdue, .sug.kind-backlog { border-left-color: var(--color-danger); }
.sug.kind-q4, .sug.kind-q3        { border-left-color: var(--color-warning); }
.sug.kind-q2                      { border-left-color: var(--color-primary); }
.sug.kind-balanced                { border-left-color: var(--color-success); }

.sug-icon { font-size: 15px; margin-top: 1px; flex-shrink: 0; color: var(--color-text-secondary); }
.sug.kind-overdue .sug-icon, .sug.kind-backlog .sug-icon { color: var(--color-danger); }
.sug.kind-q4 .sug-icon, .sug.kind-q3 .sug-icon { color: var(--color-warning); }
.sug.kind-balanced .sug-icon { color: var(--color-success); }
.sug-body { min-width: 0; }
.sug-title { font-size: 13px; font-weight: 600; color: var(--color-text); }
.sug-detail { margin-top: 2px; font-size: 12px; color: var(--color-text-secondary); line-height: 1.5; }

.empty { padding: 28px 0; text-align: center; font-size: 12.5px; color: var(--color-text-muted); }
</style>
