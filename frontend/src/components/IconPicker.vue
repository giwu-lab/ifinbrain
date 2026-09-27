<template>
  <el-popover
    placement="bottom-start"
    :width="380"
    trigger="click"
    :show-arrow="false"
    popper-class="icon-picker-popper"
  >
    <template #reference>
      <div class="icon-picker-trigger" :class="{ active: open }">
        <span class="trigger-icon">
          <el-icon v-if="currentIcon">
            <component :is="currentIcon" />
          </el-icon>
          <span v-else class="trigger-placeholder">—</span>
        </span>
        <span class="trigger-name">{{ modelValue || placeholderText }}</span>
        <el-icon class="trigger-arrow"><ArrowDown /></el-icon>
        <el-icon
          v-if="modelValue && clearable"
          class="trigger-clear"
          @click.stop="clear"
        >
          <CircleClose />
        </el-icon>
      </div>
    </template>

    <div class="icon-picker-panel">
      <el-input
        v-model="keyword"
        :placeholder="$t ? $t('system.iconSearch') || '搜索图标…' : '搜索图标…'"
        clearable
        size="default"
        class="search-input"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>

      <div class="icon-grid" @scroll="onScroll">
        <button
          v-for="name in displayed"
          :key="name"
          type="button"
          class="icon-cell"
          :class="{ selected: name === modelValue }"
          :title="name"
          @click="pick(name)"
        >
          <el-icon><component :is="resolve(name)" /></el-icon>
        </button>
        <div v-if="displayed.length === 0" class="empty">
          {{ keyword ? t('iconPicker.empty') : t('iconPicker.loading') }}
        </div>
      </div>

      <div v-if="modelValue" class="preview">
        <span class="muted">{{ t('iconPicker.selected') }}</span>
        <el-icon class="preview-icon"><component :is="currentIcon" /></el-icon>
        <span class="preview-name">{{ modelValue }}</span>
      </div>
    </div>
  </el-popover>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import * as ElIcons from '@element-plus/icons-vue';
import { ArrowDown, CircleClose, Search } from '@element-plus/icons-vue';

const { t } = useI18n();

const props = withDefaults(
  defineProps<{
    modelValue?: string | null;
    placeholder?: string;
    clearable?: boolean;
    pageSize?: number;
  }>(),
  // placeholder 默认值用空串 —— 实际值走 placeholderText computed，
  // 因为 withDefaults 的 defaults 会被 vue compiler hoist 到 setup 外面，不能引用
  // 局部 t()。
  { placeholder: '', clearable: true, pageSize: 120 }
);

// 当调用方未传 placeholder 时，回退到 i18n 的 iconPicker.placeholder。
const placeholderText = computed(() => props.placeholder || t('iconPicker.placeholder'));

const emit = defineEmits<{ 'update:modelValue': [string | null] }>();

const keyword = ref('');
const open = ref(false);
const visibleCount = ref(props.pageSize);

const allNames = computed(() =>
  Object.keys(ElIcons).filter(
    (k) => k !== 'default' && k !== 'install' && typeof (ElIcons as Record<string, unknown>)[k] !== 'function'
  )
);

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  if (!q) return allNames.value;
  return allNames.value.filter((n) => n.toLowerCase().includes(q));
});

const displayed = computed(() => filtered.value.slice(0, visibleCount.value));

function onScroll(e: Event) {
  const el = e.target as HTMLElement;
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 16) {
    if (visibleCount.value < filtered.value.length) {
      visibleCount.value = Math.min(visibleCount.value + props.pageSize, filtered.value.length);
    }
  }
}

function resolve(name?: string | null) {
  if (!name) return ElIcons.Document;
  return (ElIcons as Record<string, unknown>)[name] || ElIcons.Document;
}

const currentIcon = computed(() => resolve(props.modelValue));

function pick(name: string) {
  emit('update:modelValue', name);
  open.value = false;
}

function clear() {
  emit('update:modelValue', null);
}
</script>

<style scoped>
.icon-picker-trigger {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 180px;
  max-width: 240px;
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-elev);
  cursor: pointer;
  transition: border-color 0.15s;
  position: relative;
}
.icon-picker-trigger:hover,
.icon-picker-trigger.active {
  border-color: var(--color-primary);
}
.trigger-icon {
  display: inline-flex;
  align-items: center;
  font-size: 16px;
  color: var(--color-primary);
}
.trigger-placeholder {
  color: var(--color-text-muted);
  font-size: 13px;
}
.trigger-name {
  flex: 1;
  font-size: 13px;
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.trigger-arrow {
  font-size: 12px;
  color: var(--color-text-secondary);
}
.trigger-clear {
  font-size: 14px;
  color: var(--color-text-muted);
  cursor: pointer;
}
.trigger-clear:hover {
  color: var(--color-danger);
}

.icon-picker-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 4px;
}
.search-input :deep(.el-input__wrapper) {
  border-radius: var(--radius-sm);
}
.icon-grid {
  max-height: 280px;
  overflow-y: auto;
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 6px;
  padding: 4px;
}
.icon-cell {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 38px;
  border: 1px solid transparent;
  border-radius: var(--radius-xs);
  background: transparent;
  cursor: pointer;
  font-size: 16px;
  color: var(--color-text);
  transition: all 0.12s;
}
.icon-cell:hover {
  background: var(--color-primary-soft);
  border-color: var(--color-primary-soft);
  color: var(--color-primary);
}
.icon-cell.selected {
  background: var(--color-primary-soft);
  border-color: var(--color-primary);
  color: var(--color-primary);
}
.empty {
  grid-column: 1 / -1;
  text-align: center;
  color: var(--color-text-muted);
  padding: 24px 0;
  font-size: 13px;
}
.preview {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  border-top: 1px dashed var(--color-border-soft);
  font-size: 13px;
}
.preview-icon {
  font-size: 16px;
  color: var(--color-primary);
}
.preview-name {
  font-family: 'SF Mono', Menlo, monospace;
  font-size: 12px;
  color: var(--color-text);
}
</style>