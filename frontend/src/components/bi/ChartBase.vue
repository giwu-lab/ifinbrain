<script setup lang="ts">
/**
 * ECharts 实例生命周期统一入口。
 *
 * 所有图表组件都必须经过这里，理由（方案 §11 风险项）：
 * 1) dispose 唯一出口 —— 设计器里拖动/改属性会频繁重建组件，漏 dispose 就是实例泄漏
 * 2) ResizeObserver 唯一出口 —— 画布缩放 / 窗口 resize / 抽屉展开都要重算
 * 3) 主题色从容器 CSS 变量解析 —— 画布渲染读不到 var()，由这里用 getComputedStyle 转成实际色值
 */
import { onBeforeUnmount, onMounted, ref, shallowRef, watch, nextTick } from 'vue';
import { echarts } from './echartsEntry';
import type { EChartsType } from 'echarts/core';
import { buildChartOption, type ChartColors } from '@/utils/bi/chartOption';
import type { BiDataResp, BiWidget, DashboardTheme } from '@/api/bi';
import { presetVars } from '@/utils/bi/layout';

const props = defineProps<{
  widget: BiWidget;
  data: BiDataResp | null;
  theme: DashboardTheme;
}>();

const host = ref<HTMLDivElement | null>(null);
const chart = shallowRef<EChartsType | null>(null);
let ro: ResizeObserver | null = null;

/** 从容器上解析实际色值（default 预设下会跟随应用明暗自动变化） */
function resolveColors(el: HTMLElement, theme: DashboardTheme): ChartColors {
  const vars = presetVars(theme.preset, theme.accent);
  // 先把变量落到元素上，getComputedStyle 才拿得到
  for (const [k, v] of Object.entries(vars)) el.style.setProperty(k, v);
  const cs = getComputedStyle(el);
  const read = (name: string, fallback: string) => {
    const v = cs.getPropertyValue(name).trim();
    return v || fallback;
  };
  return {
    text: read('--bi-text', '#1f2937'),
    textSecondary: read('--bi-text-secondary', '#6b7281'),
    textMuted: read('--bi-text-muted', '#9ca3af'),
    border: read('--bi-border', '#e5e7eb'),
    accent: read('--bi-accent', '#2563eb'),
    panel: read('--bi-panel', '#ffffff'),
  };
}

function render() {
  const el = host.value;
  if (!el) return;
  const colors = resolveColors(el, props.theme);
  const option = buildChartOption(props.widget, props.data, colors);
  if (!option) return;
  if (!chart.value) {
    chart.value = echarts.init(el, undefined, { renderer: 'canvas' });
  }
  const inst = chart.value;
  if (!inst) return;
  // notMerge=true：字段映射变了要清掉旧 series，否则会残留
  inst.setOption(option, true);
}

function resize() {
  chart.value?.resize();
}

onMounted(() => {
  render();
  if (host.value && typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver(() => resize());
    ro.observe(host.value);
  }
});

onBeforeUnmount(() => {
  // 泄漏防御：卸载时必须断开观察 + 销毁实例
  ro?.disconnect();
  ro = null;
  chart.value?.dispose();
  chart.value = null;
});

// 数据 / 组件类型 / 字段映射变化 → 重绘
watch(
  () => [props.widget.type, props.widget.chart, props.widget.data, props.data],
  () => nextTick(render),
  { deep: true },
);
// 主题切换 → 重绘（色值从 CSS 变量来，需要重新 resolve）
watch(() => [props.theme.preset, props.theme.accent], () => nextTick(render));

defineExpose({ resize });
</script>

<template>
  <div ref="host" class="chart-host"></div>
</template>

<style scoped>
.chart-host {
  width: 100%;
  height: 100%;
  min-height: 0;
}
</style>