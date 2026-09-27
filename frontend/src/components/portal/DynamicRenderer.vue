<template>
  <!--
    递归渲染器：根据 layout_schema 中的节点 type 找到对应 Vue 组件并递归渲染。
    编辑器（Editor.vue）与运行时（portal/Index.vue / Preview.vue）共用同一组件。
    编辑模式下会插入选中/删除装饰（由 editorMode prop 决定）。
  -->
  <component
    v-if="resolved"
    :is="resolved"
    v-bind="resolvedProps"
    :class="['dyn-node', editorMode && selected ? 'is-selected' : '', clickable ? 'is-clickable' : '']"
    @click.stop="onClick"
  >
    <template v-if="hasChildren">
      <DynamicRenderer
        v-for="child in node.children"
        :key="child.id"
        :node="child"
        :editor-mode="editorMode"
        :selected-id="selectedId"
        :clickable="clickable"
        @select="$emit('select', $event)"
      />
    </template>
  </component>
  <div v-else class="dyn-missing">
    <span class="muted">— {{ node.type || '?' }} (no component) —</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { getBlockDef, isContainer, BLOCK_REGISTRY } from '@/registry/blocks';

export interface LayoutNode {
  id: string;
  type: string;
  props?: Record<string, any>;
  layout?: { x: number; y: number; w: number; h: number };
  children?: LayoutNode[];
}

const props = defineProps<{
  node: LayoutNode;
  editorMode?: boolean;
  selectedId?: string | null;
  clickable?: boolean;
}>();

const emit = defineEmits<{
  (e: 'select', id: string): void;
}>();

const resolved = computed(() => {
  // 'page' 是逻辑根节点：根节点 page 用 Row 容器包装样式或直接走 children
  if (props.node.type === 'page') return null; // page 节点不渲染为组件，由调用者负责渲染 children
  // 容器（row / col）走 BLOCK_REGISTRY 中的容器组件
  if (isContainer(props.node.type)) {
    return BLOCK_REGISTRY[props.node.type]?.component;
  }
  // 普通 block 走对应组件
  return getBlockDef(props.node.type)?.component;
});

const resolvedProps = computed(() => props.node.props || {});

const hasChildren = computed(() => Array.isArray(props.node.children) && props.node.children.length > 0);

const selected = computed(() => props.selectedId === props.node.id);

function onClick() {
  if (props.clickable) emit('select', props.node.id);
}
</script>

<style scoped>
.dyn-node { display: block; }
.is-clickable { cursor: pointer; }
.is-selected {
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}
.dyn-missing {
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-xs);
  padding: 12px;
  text-align: center;
  font-size: 12px;
}
</style>