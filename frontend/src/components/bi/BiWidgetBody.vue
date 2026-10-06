<script setup lang="ts">
/**
 * 组件内容分发器。设计器预览态与播放页共用，保证「设计所见 = 播放所得」。
 * 图表统一走 ChartBase（实例生命周期由它单点负责），原生组件直接渲染。
 */
import type { BiDataResp, BiWidget, DashboardTheme } from '@/api/bi';
import { isChartType } from '@/utils/bi/layout';
import ChartBase from './ChartBase.vue';
import Wkpi from './widgets/Wkpi.vue';
import Wtable from './widgets/Wtable.vue';
import Wtext from './widgets/Wtext.vue';
import Wimage from './widgets/Wimage.vue';
import Wmarquee from './widgets/Wmarquee.vue';

defineProps<{
  widget: BiWidget;
  data: BiDataResp | null;
  theme: DashboardTheme;
}>();
</script>

<template>
  <ChartBase
    v-if="isChartType(widget.type)"
    :widget="widget"
    :data="data"
    :theme="theme"
  />
  <Wkpi v-else-if="widget.type === 'kpi'" :widget="widget" :data="data" />
  <Wtable v-else-if="widget.type === 'table'" :widget="widget" :data="data" />
  <Wmarquee v-else-if="widget.type === 'marquee'" :widget="widget" :data="data" />
  <Wtext v-else-if="widget.type === 'text'" :widget="widget" />
  <Wimage v-else-if="widget.type === 'image'" :widget="widget" />
  <div v-else-if="widget.type === 'divider'" class="w-divider"></div>
</template>

<style scoped>
.w-divider {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
}
.w-divider::before {
  content: '';
  width: 100%;
  height: 1px;
  background: var(--bi-accent);
  opacity: 0.55;
}
</style>