<template>
  <Teleport to="body">
    <ul
      ref="menuRef"
      class="tab-ctx-menu"
      :style="{ left: x + 'px', top: y + 'px' }"
      @contextmenu.prevent
    >
      <li class="ctx-item" @click="emit('refresh', tab.id)">
        <el-icon><Refresh /></el-icon>
        <span>{{ t('tab.refresh') }}</span>
      </li>
      <li v-if="tab.closable" class="ctx-item" @click="emit('close', tab.id)">
        <el-icon><Close /></el-icon>
        <span>{{ t('tab.close') }}</span>
      </li>
      <li class="ctx-item" @click="emit('closeOthers', tab.id)">
        <el-icon><CircleClose /></el-icon>
        <span>{{ t('tab.closeOthers') }}</span>
      </li>
      <li class="ctx-item ctx-danger" @click="emit('closeAll')">
        <el-icon><Delete /></el-icon>
        <span>{{ t('tab.closeAll') }}</span>
      </li>
    </ul>
  </Teleport>
</template>

<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { Refresh, CircleClose, Close, Delete } from '@element-plus/icons-vue';
import type { Tab } from '@/stores/tab';

defineProps<{
  x: number;
  y: number;
  tab: Tab;
}>();

const emit = defineEmits<{
  (e: 'close', id: string): void;
  (e: 'closeOthers', id: string): void;
  (e: 'closeAll'): void;
  (e: 'refresh', id: string): void;
  (e: 'hide'): void;
}>();

const { t } = useI18n();
const menuRef = ref<HTMLElement | null>(null);

function onDocClick(e: MouseEvent) {
  if (!menuRef.value) return;
  if (!menuRef.value.contains(e.target as Node)) {
    emit('hide');
  }
}

function onEscape(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('hide');
}

onMounted(() => {
  document.addEventListener('click', onDocClick);
  document.addEventListener('contextmenu', onDocClick);
  document.addEventListener('keydown', onEscape);
});
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick);
  document.removeEventListener('contextmenu', onDocClick);
  document.removeEventListener('keydown', onEscape);
});
</script>

<style scoped>
.tab-ctx-menu {
  position: fixed;
  z-index: 9999;
  margin: 0;
  padding: 4px;
  list-style: none;
  min-width: 140px;
  background: var(--color-bg-elev);
  border: 1px solid var(--color-border-soft);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-md);
  font-size: 13px;
  color: var(--color-text);
}

.ctx-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: var(--radius-xs);
  cursor: pointer;
  transition: background 0.12s ease;
}
.ctx-item:hover {
  background: var(--color-primary-soft);
  color: var(--color-primary);
}
.ctx-item .el-icon {
  font-size: 14px;
  color: var(--color-text-muted);
}
.ctx-item:hover .el-icon {
  color: var(--color-primary);
}
.ctx-item.ctx-danger {
  color: var(--color-danger);
}
.ctx-item.ctx-danger .el-icon {
  color: var(--color-danger);
}
.ctx-item.ctx-danger:hover {
  background: var(--color-danger-soft);
  color: var(--color-danger);
}
</style>