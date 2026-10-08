<script setup lang="ts">
/**
 * 大纲侧栏 —— MarkText / Typora 的左侧目录。
 *
 * 编辑态和只读态共用一份组件，区别只在条目里带的是 `line`（源码行号）
 * 还是 `id`（渲染后的 DOM 锚点），跳转逻辑交给父组件判断。
 */
import { useI18n } from 'vue-i18n';

export interface OutlineNode {
  level: number;
  text: string;
  /** 编辑态：markdown 源码行号 */
  line?: number;
  /** 只读态：渲染结果的标题锚点 id */
  id?: string;
}

defineProps<{ items: OutlineNode[] }>();
const emit = defineEmits<{ (e: 'jump', item: OutlineNode): void }>();

const { t } = useI18n();

function onJump(item: OutlineNode) {
  emit('jump', item);
}
</script>

<template>
  <nav v-if="items.length" class="note-outline">
    <div class="outline-title">{{ t('note.outline') }}</div>
    <ul class="outline-list">
      <li
        v-for="(item, idx) in items"
        :key="`${item.id ?? item.line}-${idx}`"
        class="outline-item"
        :class="`lv-${item.level}`"
        :title="item.text"
        @click="onJump(item)"
      >
        {{ item.text }}
      </li>
    </ul>
  </nav>
</template>

<style scoped>
.note-outline {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  /* 面板现在是白色实体（--color-bg-elev），紧贴顶栏分割线会显得挤，留一点上边距 */
  padding-top: 12px;
}
.outline-title {
  font-size: 12px;
  color: var(--color-text-muted);
  padding: 0 12px 8px;
  flex-shrink: 0;
}
.outline-list {
  list-style: none;
  margin: 0;
  padding: 0 8px 24px;
  overflow-y: auto;
  min-height: 0;
}
.outline-item {
  font-size: 13px;
  line-height: 1.5;
  color: var(--color-text-secondary);
  padding: 4px 8px;
  border-radius: var(--radius-xs);
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.outline-item:hover {
  color: var(--color-primary);
  background: var(--color-primary-softer);
}
.outline-item.lv-1 { color: var(--color-text); font-weight: 600; }
.outline-item.lv-2 { padding-left: 18px; }
.outline-item.lv-3 { padding-left: 30px; }
.outline-item.lv-4,
.outline-item.lv-5,
.outline-item.lv-6 { padding-left: 42px; font-size: 12px; color: var(--color-text-muted); }
</style>