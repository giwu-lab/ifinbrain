import { defineStore } from 'pinia';
import { computed, ref, watch } from 'vue';
import { flowDefineApi, type FlowNode, type FlowEdge, type FlowJson, type NodeType, defaultNodePosition } from '@/api/flowDefine';

let nodeSeq = 0;
let edgeSeq = 0;

function newNodeId(type: NodeType): string {
  const prefix = type.toLowerCase();
  return `${prefix}_${Date.now().toString(36)}_${(nodeSeq++).toString(36)}`;
}
function newEdgeId(): string {
  return `e_${Date.now().toString(36)}_${(edgeSeq++).toString(36)}`;
}

function makeDefaultNode(type: NodeType, index: number): FlowNode {
  const labels: Record<NodeType, string> = {
    START: '开始',
    END: '结束',
    APPROVAL: '审批节点',
    CONDITION: '条件分支',
  };
  const base: FlowNode = {
    id: newNodeId(type),
    type: 'custom',
    position: defaultNodePosition(type, index),
    data: { type, label: labels[type], description: '', formCode: '' },
  };
  if (type === 'CONDITION') {
    base.data.branches = [
      { label: '是', condition: '' },
      { label: '否', condition: '' },
    ];
  }
  if (type === 'APPROVAL') {
    base.data.approvalType = 'OR';
    base.data.userIds = [];
    base.data.roleIds = [];
    base.data.orgIds = [];
  }
  return base;
}

export type DesignerMode = 'design' | 'preview' | 'json';

/**
 * 流程设计器 store（仿 mfish BpmnConfig）。
 * 持有 nodes / edges + 选中 + undo/redo + 模式 + 自动布局。
 */
export const useFlowDesignStore = defineStore('flowDesign', () => {
  const meta = ref<any | null>(null);
  const nodes = ref<FlowNode[]>([]);
  const edges = ref<FlowEdge[]>([]);
  const selectedNodeId = ref<string | null>(null);
  const selectedEdgeId = ref<string | null>(null);
  const dirty = ref(false);
  const loading = ref(false);

  const designerMode = ref<DesignerMode>('design');
  const history = ref<string[]>([]);
  const historyIndex = ref(-1);

  const selectedNode = computed<FlowNode | null>(() => nodes.value.find(n => n.id === selectedNodeId.value) || null);
  const selectedEdge = computed<FlowEdge | null>(() => edges.value.find(e => e.id === selectedEdgeId.value) || null);

  watch(selectedNodeId, () => { selectedEdgeId.value = null; });
  watch(selectedEdgeId, () => { selectedNodeId.value = null; });

  // ===================== Reset / 初始化 =====================

  function reset(item?: any) {
    meta.value = item ?? null;
    const json: FlowJson = item?.flowJson ?? { nodes: [], edges: [] };
    nodes.value = (json.nodes || []).map(n => ({
      ...n,
      position: n.position || { x: 100, y: 100 },
    }));
    edges.value = (json.edges || []).map(e => ({ ...e }));
    selectedNodeId.value = null;
    selectedEdgeId.value = null;
    dirty.value = false;
    history.value = [];
    historyIndex.value = -1;
    saveHistory();
  }

  function setLoading(v: boolean) { loading.value = v; }
  function markDirty() { dirty.value = true; }
  function clearDirty() { dirty.value = false; }
  function setMode(m: DesignerMode) { designerMode.value = m; }

  // ===================== 撤销 / 重做 =====================

  function snapshot(): string {
    return JSON.stringify({ nodes: nodes.value, edges: edges.value });
  }

  function commit() {
    if (historyIndex.value + 1 < history.value.length) {
      history.value = history.value.slice(0, historyIndex.value + 1);
    }
    history.value.push(snapshot());
    historyIndex.value = history.value.length - 1;
    dirty.value = true;
  }

  function saveHistory() {
    if (history.value.length === 0) {
      history.value = [snapshot()];
      historyIndex.value = 0;
    }
  }

  function undo() {
    if (historyIndex.value <= 0) return;
    historyIndex.value--;
    const snap = JSON.parse(history.value[historyIndex.value]);
    nodes.value = snap.nodes;
    edges.value = snap.edges;
    dirty.value = true;
  }

  function redo() {
    if (historyIndex.value >= history.value.length - 1) return;
    historyIndex.value++;
    const snap = JSON.parse(history.value[historyIndex.value]);
    nodes.value = snap.nodes;
    edges.value = snap.edges;
    dirty.value = true;
  }

  const canUndo = computed(() => historyIndex.value > 0);
  const canRedo = computed(() => historyIndex.value < history.value.length - 1);

  // ===================== 节点操作 =====================

  function addNode(type: NodeType, position?: { x: number; y: number }) {
    // 校验：START / END 只能各 1 个
    if (type === 'START' && nodes.value.some(n => n.data.type === 'START')) {
      return;
    }
    if (type === 'END' && nodes.value.some(n => n.data.type === 'END')) {
      return;
    }
    const n = makeDefaultNode(type, nodes.value.length);
    if (position) n.position = position;
    nodes.value.push(n);
    selectedNodeId.value = n.id;
    commit();
  }

  function removeNode(id: string) {
    const idx = nodes.value.findIndex(n => n.id === id);
    if (idx >= 0) nodes.value.splice(idx, 1);
    // 同步删除相关边
    edges.value = edges.value.filter(e => e.source !== id && e.target !== id);
    if (selectedNodeId.value === id) selectedNodeId.value = null;
    commit();
  }

  function updateNode(id: string, patch: Partial<FlowNode['data']>) {
    const n = nodes.value.find(x => x.id === id);
    if (!n) return;
    n.data = { ...n.data, ...patch };
    commit();
  }

  function updateNodePosition(id: string, position: { x: number; y: number }) {
    const n = nodes.value.find(x => x.id === id);
    if (!n) return;
    n.position = position;
    // 不 commit（频繁拖动时不要推 history）
    dirty.value = true;
  }

  // ===================== 边操作 =====================

  function addEdge(source: string, target: string, condition?: string): boolean {
    // 防止自环
    if (source === target) return false;
    // 防止重复边
    if (edges.value.some(e => e.source === source && e.target === target)) return false;
    const e: FlowEdge = {
      id: newEdgeId(),
      source, target,
      type: 'smoothstep',
      data: { condition: condition || '' },
    };
    edges.value.push(e);
    commit();
    return true;
  }

  function removeEdge(id: string) {
    const idx = edges.value.findIndex(e => e.id === id);
    if (idx >= 0) edges.value.splice(idx, 1);
    if (selectedEdgeId.value === id) selectedEdgeId.value = null;
    commit();
  }

  function updateEdge(id: string, patch: Partial<FlowEdge['data']>) {
    const e = edges.value.find(x => x.id === id);
    if (!e) return;
    e.data = { ...(e.data || {}), ...patch };
    commit();
  }

  // ===================== 选中 =====================

  function selectNode(id: string | null) {
    selectedNodeId.value = id;
  }
  function selectEdge(id: string | null) {
    selectedEdgeId.value = id;
  }
  function clearSelection() {
    selectedNodeId.value = null;
    selectedEdgeId.value = null;
  }

  // ===================== 自动布局（dagre） =====================

  function autoLayout(nodes2: FlowNode[], edges2: FlowEdge[]): { nodes: FlowNode[] } {
    // 动态 import dagre（避免影响主包体积）
    return import('dagre').then(({ default: dagre }) => {
      const g = new dagre.graphlib.Graph();
      g.setGraph({ rankdir: 'TB', nodesep: 60, ranksep: 80, edgesep: 10 });
      g.setDefaultEdgeLabel(() => ({}));
      for (const n of nodes2) g.setNode(n.id, { width: 180, height: 60 });
      for (const e of edges2) g.setEdge(e.source, e.target);
      dagre.layout(g);
      const newNodes = nodes2.map(n => {
        const pos = g.node(n.id);
        return pos ? { ...n, position: { x: pos.x - 90, y: pos.y - 30 } } : n;
      });
      return { nodes: newNodes };
    }) as any;
  }

  // ===================== 校验 =====================

  function validate(): string | null {
    if (!nodes.value.some(n => n.data.type === 'START')) return '流程必须包含 1 个开始节点';
    if (!nodes.value.some(n => n.data.type === 'END')) return '流程必须包含 1 个结束节点';
    for (const n of nodes.value) {
      if (n.data.type === 'APPROVAL') {
        const hasUser = n.data.userIds && n.data.userIds.length > 0;
        const hasRole = n.data.roleIds && n.data.roleIds.length > 0;
        const hasOrg = n.data.orgIds && n.data.orgIds.length > 0;
        if (!hasUser && !hasRole && !hasOrg) {
          return `审批节点「${n.data.label || n.id}」必须配置至少一个审批人/角色/组织`;
        }
      }
    }
    return null;
  }

  return {
    meta, nodes, edges,
    selectedNodeId, selectedEdgeId, selectedNode, selectedEdge,
    dirty, loading, designerMode,
    history, historyIndex, canUndo, canRedo,
    reset, setLoading, markDirty, clearDirty, setMode,
    commit, saveHistory, undo, redo,
    addNode, removeNode, updateNode, updateNodePosition,
    addEdge, removeEdge, updateEdge,
    selectNode, selectEdge, clearSelection,
    autoLayout, validate,
  };
});

export type { FlowNode, FlowEdge, FlowJson, NodeType } from '@/api/flowDefine';