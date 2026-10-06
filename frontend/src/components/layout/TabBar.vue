<template>
  <div class="tab-bar">
    <div ref="scrollerRef" class="tab-scroller">
      <div
        v-for="tab in tabs"
        :key="tab.id"
        :class="['tab-item', { active: tab.id === activeId, closable: tab.closable }]"
        @click="$emit('select', tab.id)"
        @contextmenu.prevent="openContextMenu($event, tab)"
      >
        <el-icon v-if="tab.affix" class="tab-affix-icon" :title="t('layout.home')"><Histogram /></el-icon>
        <span class="tab-label" :title="labelOf(tab)">{{ labelOf(tab) }}</span>
        <button
          v-if="tab.closable"
          type="button"
          class="tab-close"
          :aria-label="`Close ${labelOf(tab)}`"
          @click.stop="$emit('close', tab.id)"
        >
          <el-icon><Close /></el-icon>
        </button>
      </div>
    </div>

    <div class="tab-actions">
      <el-tooltip :content="t('tab.refresh')" placement="bottom">
        <el-button text :icon="Refresh" size="small" @click="$emit('refresh')" />
      </el-tooltip>
      <el-tooltip :content="t('tab.closeOthers')" placement="bottom">
        <el-button text :icon="CircleClose" size="small" :disabled="tabs.length <= 1" @click="$emit('closeOthers', activeId)" />
      </el-tooltip>
      <el-tooltip :content="t('tab.closeAll')" placement="bottom">
        <el-button text :icon="Delete" size="small" :disabled="!hasClosable" @click="$emit('closeAll')" />
      </el-tooltip>
    </div>

    <TabContextMenu
      v-if="ctx.visible"
      :x="ctx.x"
      :y="ctx.y"
      :tab="ctx.tab!"
      @close="onCtxClose"
      @close-others="onCtxCloseOthers"
      @refresh="onCtxRefresh"
      @hide="ctx.visible = false"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, reactive, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { Close, Refresh, CircleClose, Delete, Histogram } from '@element-plus/icons-vue';
import { useTabStore, type Tab } from '@/stores/tab';
import { useArchiveStore } from '@/stores/archive';
import TabContextMenu from './TabContextMenu.vue';

const { t } = useI18n();
const tabStore = useTabStore();
const archiveStore = useArchiveStore();

onMounted(() => {
  archiveStore.load().catch(() => { /* 静默失败，tab 标签回退到 path */ });
});

const emit = defineEmits<{
  (e: 'select', id: string): void;
  (e: 'close', id: string): void;
  (e: 'closeOthers', id: string): void;
  (e: 'closeAll'): void;
  (e: 'refresh', id?: string): void;
}>();

const tabs = computed(() => tabStore.tabs);
const activeId = computed(() => tabStore.activeId);
const hasClosable = computed(() => tabs.value.some((t) => t.closable));

const scrollerRef = ref<HTMLElement | null>(null);

const ctx = reactive<{
  visible: boolean;
  x: number;
  y: number;
  tab: Tab | null;
}>({
  visible: false,
  x: 0,
  y: 0,
  tab: null,
});

function labelOf(tab: Tab): string {
  // /documents/:slug —— 按 slug 解析档案库实际名称（admin 在档案库管理里设置的中文名）
  const m = /^\/documents\/([^/]+)$/.exec(tab.id);
  if (m && m[1] !== 'archive') {
    const a = archiveStore.bySlug(m[1]);
    if (a) return a.name;
  }
  const v = t(tab.nameKey);
  return v === tab.nameKey ? tab.id : v;
}

function openContextMenu(e: MouseEvent, tab: Tab) {
  ctx.visible = true;
  ctx.x = e.clientX;
  ctx.y = e.clientY;
  ctx.tab = tab;
}

function onCtxClose(id: string) {
  ctx.visible = false;
  emit('close', id);
}

function onCtxCloseOthers(id: string) {
  ctx.visible = false;
  emit('closeOthers', id);
}

function onCtxRefresh(id: string) {
  ctx.visible = false;
  emit('refresh', id);
}
</script>

<style scoped>
.tab-bar {
  display: flex;
  align-items: stretch;
  background: var(--color-bg);
  border-bottom: 1px solid var(--color-border-soft);
  height: 38px;
  position: relative;
  z-index: 10;
}

.tab-scroller {
  flex: 1;
  display: flex;
  align-items: stretch;
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-width: thin;
  scroll-behavior: smooth;
  padding: 0 4px;
  gap: 2px;
}
.tab-scroller::-webkit-scrollbar { height: 3px; }
.tab-scroller::-webkit-scrollbar-thumb { background: var(--color-border); border-radius: 2px; }

.tab-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  height: 30px;
  margin-top: 4px;
  border-radius: var(--radius-sm) var(--radius-sm) 0 0;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
  user-select: none;
  border: 1px solid transparent;
  border-bottom: none;
  transition: background 0.15s ease, color 0.15s ease;
  flex-shrink: 0;
  max-width: 200px;
}

.tab-item:hover {
  background: var(--color-primary-soft);
  color: var(--color-primary);
}

.tab-item.active {
  background: var(--color-bg-elev);
  color: var(--color-primary);
  border-color: var(--color-border-soft);
  border-bottom-color: var(--color-bg-elev);
  font-weight: 500;
  position: relative;
  z-index: 1;
}

.tab-item.active::after {
  content: '';
  position: absolute;
  left: 0; right: 0; top: 0;
  height: 2px;
  background: var(--color-primary);
  border-radius: 2px 2px 0 0;
}

.tab-label {
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 160px;
}

.tab-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border: none;
  background: transparent;
  border-radius: 50%;
  cursor: pointer;
  color: var(--color-text-muted);
  font-size: 12px;
  transition: background 0.15s ease, color 0.15s ease;
  flex-shrink: 0;
}
.tab-close:hover {
  background: rgba(239, 68, 68, 0.12);
  color: var(--color-danger);
}
.tab-item.active .tab-close:hover {
  background: rgba(239, 68, 68, 0.15);
}

.tab-affix-icon {
  font-size: 13px;
  color: var(--color-text-muted);
  flex-shrink: 0;
}
.tab-item.active .tab-affix-icon {
  color: var(--color-primary);
}

.tab-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 0 6px;
  border-left: 1px solid var(--color-border-soft);
  background: var(--color-bg);
}
.tab-actions :deep(.el-button) {
  padding: 4px;
  min-height: auto;
  height: 28px;
  width: 28px;
}
</style>