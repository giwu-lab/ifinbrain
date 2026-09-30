<!--
  BpmnCanvas —— 基于 Vue Flow + dagre 的流程画布（仿 mfish BpmnConfig）。
  - 节点拖入 / 连线（connect event）
  - 点击节点 / 点击边 → emit 选中
  - pane 点击 → 清空选中
  - 节点位置变更 → store.updateNodePosition
-->
<template>
  <div class="bpmn-canvas">
    <VueFlow
      v-model:nodes="nodesLocal"
      v-model:edges="edgesLocal"
      :node-types="nodeTypes"
      :default-viewport="{ zoom: 1, x: 0, y: 0 }"
      :min-zoom="0.3"
      :max-zoom="2"
      :fit-view-on-init="true"
      :delete-key-code="null"
      :nodes-draggable="true"
      :nodes-connectable="true"
      :elements-selectable="true"
      @connect="onConnect"
      @node-click="onNodeClick"
      @edge-click="onEdgeClick"
      @pane-click="onPaneClick"
      @nodes-change="onNodesChange"
      @edges-change="onEdgesChange"
    >
      <Background :gap="12" :size="1" />
      <Controls />
      <MiniMap />
    </VueFlow>
  </div>
</template>

<script setup lang="ts">
import { computed, markRaw } from 'vue';
import {
  VueFlow, type Node, type Edge, type Connection,
  type NodeChange, type EdgeChange
} from '@vue-flow/core';
import { Background } from '@vue-flow/background';
import { Controls } from '@vue-flow/controls';
import { MiniMap } from '@vue-flow/minimap';
import '@vue-flow/core/dist/style.css';
import '@vue-flow/core/dist/theme-default.css';
import '@vue-flow/controls/dist/style.css';
import '@vue-flow/minimap/dist/style.css';
import { useFlowDesignStore } from '@/stores/flowDesign';
import type { FlowNode, FlowEdge } from '@/api/flowDefine';
import BaseFlowNode from './nodes/BaseFlowNode.vue';

const store = useFlowDesignStore();

const nodeTypes = markRaw({
  custom: markRaw(BaseFlowNode as any),
});

const nodesLocal = computed<Node[]>({
  get: () => store.nodes.map((n) => ({
    id: n.id,
    type: 'custom',
    position: n.position || { x: 100, y: 100 },
    data: n.data,
    label: '',
  })) as any,
  set: () => { /* 由 store 内部方法管理 */ },
});

const edgesLocal = computed<Edge[]>({
  get: () => store.edges.map((e) => ({
    id: e.id,
    source: e.source,
    target: e.target,
    type: e.type || 'smoothstep',
    label: e.data?.condition || '',
    data: e.data,
  })),
  set: () => { /* 由 store 内部方法管理 */ },
});

function onConnect(params: Connection) {
  if (!params.source || !params.target) return;
  store.addEdge(params.source, params.target, '');
}

function onNodeClick(ev: any) {
  store.selectNode(ev.node?.id ?? null);
}

function onEdgeClick(ev: any) {
  store.selectEdge(ev.edge?.id ?? null);
}

function onPaneClick() {
  store.clearSelection();
}

function onNodesChange(changes: NodeChange[]) {
  for (const ch of changes) {
    if (ch.type === 'position' && ch.position) {
      store.updateNodePosition(ch.id, { x: ch.position.x, y: ch.position.y });
    } else if (ch.type === 'remove') {
      store.removeNode(ch.id);
    }
  }
}

function onEdgesChange(changes: EdgeChange[]) {
  for (const ch of changes) {
    if (ch.type === 'remove') {
      store.removeEdge(ch.id);
    }
  }
}
</script>

<style scoped>
.bpmn-canvas {
  width: 100%;
  height: 100%;
  background: var(--color-bg-soft, #fafafa);
}
:deep(.vue-flow) { background: var(--color-bg-soft, #fafafa); }
:deep(.vue-flow__node) {
  border: none;
  padding: 0;
  background: transparent;
  box-shadow: none;
  border-radius: 0;
}
:deep(.vue-flow__node.selected .flow-node) {
  box-shadow: 0 0 0 2px var(--color-primary), 0 2px 8px rgba(94, 53, 177, 0.25);
}
:deep(.vue-flow__edge-path) {
  stroke-width: 1.5;
}
:deep(.vue-flow__edge.selected .vue-flow__edge-path) {
  stroke: var(--color-primary);
}
:deep(.vue-flow__edge-textwrapper) {
  font-size: 11px;
  font-weight: 500;
}
:deep(.vue-flow__minimap) {
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}
:deep(.vue-flow__controls) {
  box-shadow: 0 1px 4px rgba(0,0,0,0.08);
  border-radius: var(--radius-sm);
}
</style>