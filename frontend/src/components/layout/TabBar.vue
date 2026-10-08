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
/* ---------------------------------------------------------------
 * 多标签栏视觉区分
 *
 * 原来这里 background: var(--color-bg)，和 `.main` 的内容底色完全相同
 * （4 个 layout 里 Sidebar / TopNav / HeaderSidebar 都是 --color-bg），
 * tab 栏和页面正文糊成一块，看不出这是独立的导航层。
 *
 * 改成三处一起下手，缺一不可：
 *   1. 底色换成 --color-bg-elev（白）—— 与灰色内容区拉开明度差
 *   2. border-bottom 从 -soft 提到 -border + 一道浅投影 —— 边界线始终可见
 *      （FloatingSidebarLayout 的 .main-inner 本身就是白底，靠底色差不出来，
 *       必须靠这条边线兜底）
 *   3. 激活态改成主色浅底 —— 栏变白之后，原来的「白 tab + 白栏」会互相隐身
 *
 * 高度保持 38px 不动：列表页 §7.2 的 th sticky 是按 top: 92px 算的，
 * 改高度会连带把所有列表页的表头吸顶位置打歪。
 * ------------------------------------------------------------- */
.tab-bar {
  display: flex;
  align-items: stretch;
  background: var(--color-bg-elev);
  border-bottom: 1px solid var(--color-border);
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.06);
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
  padding: 0 12px;
  height: 30px;
  margin-top: 4px;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
  user-select: none;
  transition: background 0.15s ease, color 0.15s ease;
  flex-shrink: 0;
  max-width: 200px;
  position: relative;
}

.tab-item:hover {
  background: var(--color-primary-softer);
  color: var(--color-primary);
}

.tab-item.active {
  background: var(--color-primary-soft);
  color: var(--color-primary);
  font-weight: 600;
}

/* 激活 tab 底部的主色指示条：贴齐 tab 下沿，和栏底边线合成一条完整的选中带 */
.tab-item.active::after {
  content: '';
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: -4px;
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
  background: var(--color-bg-elev);
}
.tab-actions :deep(.el-button) {
  padding: 4px;
  min-height: auto;
  height: 28px;
  width: 28px;
}
</style>