import type { BiDataResp, BiWidget } from '@/api/bi';
import { aggregate, applyTransform, colIndex } from './layout';

/**
 * ECharts 画布渲染读不到 CSS 变量，由调用方（ChartBase）用 getComputedStyle
 * 从容器上解析出实际色值传进来 —— 这样大屏主题切换能实时生效，且 default
 * 预设下自动跟随应用明暗（AGENTS §7.3.6）。
 */
export interface ChartColors {
  text: string;
  textSecondary: string;
  textMuted: string;
  border: string;
  accent: string;
  panel: string;
}

/** 分类色板：主色打头，后面接一组可区分的中性/暖冷色 */
export const CATEGORICAL = [
  '#38bdf8', '#34d399', '#fbbf24', '#f472b6',
  '#a78bfa', '#fb923c', '#2dd4bf', '#f87171',
  '#60a5fa', '#c084fc', '#4ade80', '#facc15',
];

/** 主题主色打头 */
export function palette(accent: string): string[] {
  return [accent, ...CATEGORICAL.filter((c) => c.toLowerCase() !== accent.toLowerCase())];
}

/** KPI / 表格的数字格式化：12345 → 1.23万 */
export function formatNumber(v: any, digits = 2): string {
  if (v === null || v === undefined || v === '') return '-';
  const n = Number(v);
  if (!Number.isFinite(n)) return String(v);
  const abs = Math.abs(n);
  if (abs >= 1e8) return (n / 1e8).toFixed(digits) + '亿';
  if (abs >= 1e4) return (n / 1e4).toFixed(digits) + '万';
  if (Number.isInteger(n)) return String(n);
  return n.toFixed(digits);
}

interface Prepared {
  rows: any[][];
  /** 分类轴取值 */
  cats: string[];
  /** 每组指标值 */
  series: { name: string; data: number[] }[];
  labelIdx: number;
  valueIdx: number;
  yIdx: number[];
  xIdx: number;
  labels: string[];
  types: string[];
}

/**
 * 把 BiDataResp 按 widget.data.mapping 整形。
 * 指标取 y（多列多 series），分类取 x（无 x 则用行号）。
 */
export function prepareData(widget: BiWidget, data: BiDataResp | null): Prepared | null {
  if (!data || !data.rows?.length) return null;
  const bind = widget.data;
  if (!bind) return null;

  const columns = data.columns || [];
  const labels = columns.map((c) => c.label || c.key);
  const types = columns.map((c) => c.type || 'string');

  let xIdx = colIndex(columns, bind.mapping.x);
  let yIdx = (bind.mapping.y || []).map((k) => colIndex(columns, k)).filter((i) => i >= 0);
  let labelIdx = colIndex(columns, bind.mapping.name);
  let valueIdx = colIndex(columns, bind.mapping.value);

  // 指标列：优先 y[]；饼/环/漏斗/树图用 value 兜底
  if (!yIdx.length && valueIdx >= 0) yIdx = [valueIdx];
  if (!yIdx.length) {
    // 一个指标都没映射 → 退化为「第一个数值型列」，避免整图空白
    const firstNum = types.findIndex((t) => t === 'number');
    if (firstNum >= 0) yIdx = [firstNum];
  }
  // series 名要保留聚合前的原始列名
  const metricNames = yIdx.map((i) => labels[i] || `指标${i + 1}`);

  let rows: any[][] = data.rows;

  // 聚合会改变行形状（[groupKey, agg1, agg2...]），列号必须同步重映射
  const agg = bind.agg && bind.agg !== 'none' ? bind.agg : null;
  if (agg && xIdx >= 0 && yIdx.length) {
    rows = aggregate(rows, xIdx, yIdx, agg);
    xIdx = 0;
    labelIdx = 0;
    yIdx = yIdx.map((_, k) => k + 1);
    valueIdx = yIdx[0] ?? -1;
  }

  // 排序字段要映射到当前（可能已重映射的）行形状上
  const sortField = bind.transform?.sortBy?.field;
  let sortIdx = -1;
  if (sortField) {
    if (sortField === bind.mapping.x) sortIdx = xIdx;
    else if (sortField === bind.mapping.value) sortIdx = valueIdx;
    else sortIdx = yIdx.find((_, k) => labels[k] === sortField) ?? yIdx[0] ?? -1;
  }
  rows = applyTransform(rows, sortIdx, bind.transform?.sortBy?.dir || 'desc',
    bind.transform?.limit, bind.transform?.topN);

  // 分类轴
  const cats = rows.map((r, i) => (xIdx >= 0 ? String(r[xIdx] ?? '') : String(i + 1)));

  const series = yIdx.map((i, k) => ({
    name: metricNames[k] || `指标${k + 1}`,
    data: rows.map((r) => Number(r[i] ?? 0)),
  }));

  return { rows, cats, series, labelIdx, valueIdx, yIdx, xIdx, labels, types };
}

/** 主入口：widget + 数据 → ECharts option */
export function buildChartOption(
  widget: BiWidget,
  data: BiDataResp | null,
  c: ChartColors,
): Record<string, any> | null {
  const p = prepareData(widget, data);
  if (!p) return null;
  const colors = palette(c.accent);
  const user = widget.chart || {};

  const base = {
    color: colors,
    textStyle: { color: c.textSecondary, fontFamily: 'inherit' },
    animationDuration: 400,
    ...user,
  };

  const tooltipAxis = {
    trigger: 'axis' as const,
    axisPointer: { type: 'shadow' as const },
    backgroundColor: c.panel,
    borderColor: c.border,
    textStyle: { color: c.text },
  };

  switch (widget.type) {
    case 'bar':
    case 'hbar': {
      const isH = widget.type === 'hbar';
      const catAxis = {
        type: 'category' as const,
        data: p.cats,
        axisLabel: { color: c.textMuted, hideOverlap: true },
        axisLine: { lineStyle: { color: c.border } },
        axisTick: { show: false },
      };
      const valAxis = {
        type: 'value' as const,
        splitLine: { lineStyle: { color: c.border, type: 'dashed' as const } },
        axisLabel: { color: c.textMuted },
      };
      return {
        ...base,
        tooltip: tooltipAxis,
        legend: { show: p.series.length > 1, top: 0, textStyle: { color: c.textSecondary } },
        grid: { left: 8, right: 12, top: p.series.length > 1 ? 28 : 12, bottom: 4, containLabel: true },
        xAxis: isH ? valAxis : catAxis,
        yAxis: isH ? catAxis : valAxis,
        series: p.series.map((s) => ({
          name: s.name,
          type: 'bar',
          data: s.data,
          barMaxWidth: 36,
          itemStyle: { borderRadius: isH ? [0, 3, 3, 0] : [3, 3, 0, 0] },
          ...(user.barStyle || {}),
        })),
      };
    }

    case 'line':
    case 'area': {
      const isArea = widget.type === 'area';
      return {
        ...base,
        tooltip: tooltipAxis,
        legend: { show: p.series.length > 1, top: 0, textStyle: { color: c.textSecondary } },
        grid: { left: 8, right: 12, top: p.series.length > 1 ? 28 : 12, bottom: 4, containLabel: true },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: p.cats,
          axisLabel: { color: c.textMuted, hideOverlap: true },
          axisLine: { lineStyle: { color: c.border } },
          axisTick: { show: false },
        },
        yAxis: {
          type: 'value',
          splitLine: { lineStyle: { color: c.border, type: 'dashed' } },
          axisLabel: { color: c.textMuted },
        },
        series: p.series.map((s) => ({
          name: s.name,
          type: 'line',
          smooth: true,
          showSymbol: p.cats.length <= 40,
          lineStyle: { width: 2 },
          areaStyle: isArea ? { opacity: 0.22 } : undefined,
          ...(user.lineStyle || {}),
        })),
      };
    }

    case 'pie':
    case 'donut': {
      const idx = firstMetricCol(p);
      const nameIdx = p.labelIdx >= 0 ? p.labelIdx : p.xIdx;
      const data = p.rows.map((r, i) => ({
        name: nameIdx >= 0 ? String(r[nameIdx] ?? p.cats[i]) : p.cats[i],
        value: Number(r[idx] ?? 0),
      }));
      return {
        ...base,
        tooltip: { trigger: 'item', backgroundColor: c.panel, borderColor: c.border, textStyle: { color: c.text } },
        legend: {
          orient: 'vertical',
          right: 8,
          top: 'center',
          textStyle: { color: c.textSecondary },
          ...(user.legend || {}),
        },
        series: [{
          type: 'pie',
          radius: widget.type === 'donut' ? ['46%', '68%'] : ['0%', '68%'],
          center: ['38%', '52%'],
          avoidLabelOverlap: true,
          itemStyle: { borderColor: c.panel, borderWidth: 2 },
          label: { color: c.textSecondary, formatter: '{b} {d}%' },
          data,
          ...(user.pieStyle || {}),
        }],
      };
    }

    case 'scatter': {
      const xs = p.series[0]?.data ?? [];
      const ys = p.series[1]?.data ?? xs;
      return {
        ...base,
        tooltip: tooltipAxis,
        grid: { left: 8, right: 12, top: 12, bottom: 4, containLabel: true },
        xAxis: {
          type: 'value',
          name: p.series[0]?.name,
          nameTextStyle: { color: c.textMuted },
          splitLine: { lineStyle: { color: c.border, type: 'dashed' } },
          axisLabel: { color: c.textMuted },
        },
        yAxis: {
          type: 'value',
          name: p.series[1]?.name,
          nameTextStyle: { color: c.textMuted },
          splitLine: { lineStyle: { color: c.border, type: 'dashed' } },
          axisLabel: { color: c.textMuted },
        },
        series: [{
          type: 'scatter',
          symbolSize: 10,
          data: xs.map((x, i) => [x, ys[i] ?? 0]),
        }],
      };
    }

    case 'radar': {
      return {
        ...base,
        tooltip: { trigger: 'item', backgroundColor: c.panel, borderColor: c.border, textStyle: { color: c.text } },
        legend: { show: p.series.length > 1, bottom: 0, textStyle: { color: c.textSecondary } },
        radar: {
          indicator: p.cats.map((name) => ({ name, max: undefined })),
          axisName: { color: c.textSecondary },
          splitLine: { lineStyle: { color: c.border } },
          splitArea: { areaStyle: { color: ['transparent'] } },
          axisLine: { lineStyle: { color: c.border } },
        },
        series: [{
          type: 'radar',
          data: p.series.map((s) => ({ name: s.name, value: s.data })),
          areaStyle: { opacity: 0.18 },
        }],
      };
    }

    case 'gauge': {
      const val = p.series[0]?.data?.[0] ?? 0;
      return {
        ...base,
        series: [{
          type: 'gauge',
          min: 0,
          max: Math.max(val * 1.25, 100),
          progress: { show: true, width: 12, itemStyle: { color: c.accent } },
          axisLine: { lineStyle: { width: 12, color: [[1, c.border]] } },
          axisTick: { show: false },
          splitLine: { length: 10, lineStyle: { color: c.border, width: 1 } },
          axisLabel: { color: c.textMuted },
          pointer: { itemStyle: { color: c.accent } },
          detail: {
            valueAnimation: true,
            color: c.text,
            formatter: (v: number) => formatNumber(v),
            fontSize: 22,
          },
          data: [{ value: val, name: p.series[0]?.name || '' }],
          title: { color: c.textSecondary },
        }],
      };
    }

    case 'funnel': {
      const idx = firstMetricCol(p);
      const nameIdx = p.labelIdx >= 0 ? p.labelIdx : p.xIdx;
      const data = p.rows
        .map((r, i) => ({ name: nameIdx >= 0 ? String(r[nameIdx] ?? p.cats[i]) : p.cats[i], value: Number(r[idx] ?? 0) }))
        .sort((a, b) => b.value - a.value);
      return {
        ...base,
        tooltip: { trigger: 'item', backgroundColor: c.panel, borderColor: c.border, textStyle: { color: c.text } },
        series: [{
          type: 'funnel',
          left: '8%', right: '8%', top: 12, bottom: 8,
          label: { color: c.textSecondary },
          itemStyle: { borderColor: c.panel, borderWidth: 1 },
          data,
        }],
      };
    }

    case 'treemap': {
      const idx = firstMetricCol(p);
      const nameIdx = p.labelIdx >= 0 ? p.labelIdx : p.xIdx;
      return {
        ...base,
        tooltip: { trigger: 'item', backgroundColor: c.panel, borderColor: c.border, textStyle: { color: c.text } },
        series: [{
          type: 'treemap',
          roam: false,
          nodeClick: false,
          breadcrumb: { show: false },
          itemStyle: { borderColor: c.panel, borderWidth: 1, gapWidth: 1 },
          label: { color: c.text, fontSize: 12 },
          data: p.rows.map((r, i) => ({
            name: nameIdx >= 0 ? String(r[nameIdx] ?? p.cats[i]) : p.cats[i],
            value: Number(r[idx] ?? 0),
          })),
        }],
      };
    }

    default:
      return null;
  }
}

/** 饼/环/漏斗/树图：取第一个指标列 */
function firstMetricCol(p: Prepared): number {
  // series[0].data 是每列独立数组，取其在原 rows 中的列号
  return p.yIdx[0] ?? 0;
}