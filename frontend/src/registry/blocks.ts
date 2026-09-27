/**
 * Block 注册表 —— 把后端 sys_portal_block 与前端 Vue 组件绑定。
 * 每个 block 形如 { type, component, defaultProps }。
 *
 * 编辑器根据此表生成「左侧组件库」；
 * DynamicRenderer 根据 type 找到 component 渲染。
 *
 * 注意：运行时（DynamicRenderer）必须保持此表存在；编辑器从后端
 * /api/design/portal/blocks 拉取元信息（name/icon/category/configSchema），
 * 渲染时仍依赖本表的 component。
 */
import type { Component } from 'vue';

import StatCard from '@/components/portal/blocks/StatCard.vue';
import ChartCard from '@/components/portal/blocks/ChartCard.vue';
import DataTableCard from '@/components/portal/blocks/DataTableCard.vue';
import QuickLinks from '@/components/portal/blocks/QuickLinks.vue';
import TextBlock from '@/components/portal/blocks/TextBlock.vue';
import IFrameCard from '@/components/portal/blocks/IFrameCard.vue';
import RowLayout from '@/components/portal/blocks/layout/Row.vue';
import ColLayout from '@/components/portal/blocks/layout/Col.vue';

export interface BlockDefinition {
  /** 与后端 sys_portal_block.type 一致 */
  type: string;
  /** Vue 组件 */
  component: Component;
  /** 新拖入画布时的默认 props；与后端 default_config 字段语义一致 */
  defaultProps: Record<string, unknown>;
}

export const BLOCK_REGISTRY: Record<string, BlockDefinition> = {
  'stat-card':   { type: 'stat-card',   component: StatCard,   defaultProps: { title: '指标', value: 0, suffix: '', trend: '', color: 'primary' } },
  'chart-card':  { type: 'chart-card',  component: ChartCard,  defaultProps: { title: '图表', kind: 'bar', data: '[]', xField: 'name', yField: 'value' } },
  'data-table':  { type: 'data-table',  component: DataTableCard, defaultProps: { title: '表格', queryId: 0, pageSize: 20, columns: '[]' } },
  'quick-links': { type: 'quick-links', component: QuickLinks, defaultProps: { title: '快捷入口', links: '[]' } },
  'text-block':  { type: 'text-block',  component: TextBlock,  defaultProps: { title: '文本块', content: '在此输入文本', variant: 'plain' } },
  'iframe':      { type: 'iframe',      component: IFrameCard, defaultProps: { title: '嵌入页', src: '', height: 420 } },
  // layout 容器
  'row': { type: 'row', component: RowLayout, defaultProps: { gap: 16 } },
  'col': { type: 'col', component: ColLayout, defaultProps: { span: 12 } },
};

export function getBlockDef(type: string): BlockDefinition | undefined {
  return BLOCK_REGISTRY[type];
}

export function isContainer(type: string): boolean {
  return type === 'page' || type === 'row' || type === 'col';
}