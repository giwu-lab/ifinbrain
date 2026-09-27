import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import type { LayoutNode } from '@/components/portal/DynamicRenderer.vue';
import type { PortalBlockMeta } from '@/api/portalPage';

const DEFAULT_ROOT: LayoutNode = {
  id: 'root',
  type: 'page',
  props: { cols: 12, gap: 16, padding: 24 },
  children: [],
};

/**
 * 页面设计器编辑器草稿 store。
 * 持有一份 root LayoutNode + 当前选中节点 id；
 * 树操作函数（addChild / removeNode / updateNode）支持嵌套。
 */
export const usePortalEditorStore = defineStore('portalEditor', () => {
  const root = ref<LayoutNode>(deepClone(DEFAULT_ROOT));
  const selectedId = ref<string | null>(null);
  const blockMetas = ref<PortalBlockMeta[]>([]);
  const dirty = ref(false);
  const loading = ref(false);

  /** 当前选中节点（按 id 遍历树） */
  const selectedNode = computed<LayoutNode | null>(() => findById(root.value, selectedId.value));

  function reset(schema?: string) {
    if (schema) {
      try {
        const parsed = JSON.parse(schema);
        root.value = parsed.root || parsed;
        if (!root.value.children) root.value.children = [];
      } catch {
        root.value = deepClone(DEFAULT_ROOT);
      }
    } else {
      root.value = deepClone(DEFAULT_ROOT);
    }
    selectedId.value = null;
    dirty.value = false;
  }

  function setBlocks(blocks: PortalBlockMeta[]) {
    blockMetas.value = blocks;
  }

  function setLoading(v: boolean) {
    loading.value = v;
  }

  function markDirty() { dirty.value = true; }
  function clearDirty() { dirty.value = false; }

  function select(id: string | null) {
    selectedId.value = id;
  }

  /**
   * 给指定父节点（按 parentId 查找）添加一个子节点；
   * 如 parentId 为 null/undefined，则挂到 root。
   */
  function addChild(parentId: string | null | undefined, child: LayoutNode) {
    const parent = parentId ? findById(root.value, parentId) : root.value;
    if (!parent) return;
    if (!parent.children) parent.children = [];
    parent.children.push(child);
    selectedId.value = child.id;
    markDirty();
  }

  /** 在 root 下添加（编辑器主用：拖入画布） */
  function addToRoot(child: LayoutNode) {
    addChild('root', child);
  }

  /** 删除指定 id 节点 */
  function removeNode(id: string) {
    const parent = findParent(root.value, id);
    if (!parent || !parent.children) return;
    const idx = parent.children.findIndex(c => c.id === id);
    if (idx >= 0) parent.children.splice(idx, 1);
    if (selectedId.value === id) selectedId.value = null;
    markDirty();
  }

  /** 整体替换某节点（属性面板用） */
  function replaceNode(next: LayoutNode) {
    const parent = findParent(root.value, next.id);
    if (!parent || !parent.children) {
      // 选中的是 root 自己
      if (next.id === root.value.id) root.value = next;
      return;
    }
    const idx = parent.children.findIndex(c => c.id === next.id);
    if (idx >= 0) parent.children[idx] = next;
    markDirty();
  }

  /** 当前 layout 的序列化（给后端 PUT） */
  function serialize(): string {
    return JSON.stringify({ version: 1, root: root.value });
  }

  // ----- 工具 -----

  function deepClone<T>(v: T): T { return JSON.parse(JSON.stringify(v)); }

  function findById(node: LayoutNode, id: string | null | undefined): LayoutNode | null {
    if (!id) return null;
    if (node.id === id) return node;
    if (!node.children) return null;
    for (const c of node.children) {
      const found = findById(c, id);
      if (found) return found;
    }
    return null;
  }

  function findParent(node: LayoutNode, childId: string): LayoutNode | null {
    if (!node.children) return null;
    for (const c of node.children) {
      if (c.id === childId) return node;
      const found = findParent(c, childId);
      if (found) return found;
    }
    return null;
  }

  /** 给新节点生成 id（避免冲突） */
  function nextId(prefix: string): string {
    return `${prefix}_${Math.random().toString(36).slice(2, 8)}`;
  }

  return {
    root,
    selectedId,
    selectedNode,
    blockMetas,
    dirty,
    loading,
    reset,
    setBlocks,
    setLoading,
    markDirty,
    clearDirty,
    select,
    addChild,
    addToRoot,
    removeNode,
    replaceNode,
    serialize,
    nextId,
  };
});