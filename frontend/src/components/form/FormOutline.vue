<!--
  FormOutline —— 大纲树（mfish 风格）
  显示当前 fields 列表，按 key 缩进，hover 显示操作按钮，点击选中。
-->
<template>
  <aside class="form-outline">
    <div class="outline-header">
      <span class="outline-title">{{ t('formDesigner.outline') }}</span>
      <span class="outline-count muted">{{ store.fields.length }}</span>
    </div>
    <div v-if="!store.fields.length" class="outline-empty muted">
      {{ t('formDesigner.outlineEmpty') }}
    </div>
    <div v-else class="outline-scroll">
      <div
        v-for="(f, idx) in store.fields"
        :key="f.id"
        class="outline-item-row"
        :class="{ active: store.selectedId === f.id }"
        @click="select(f.id)"
      >
        <span class="outline-item-icon">
          <el-icon><component :is="resolveIcon(FIELD_TYPE_META[f.type].icon)" /></el-icon>
        </span>
        <span class="outline-item-text">
          <span class="outline-item-label">{{ f.label || t('formDesigner.unnamed') }}</span>
          <span v-if="f.required" class="outline-item-required">*</span>
          <span class="outline-item-key muted">{{ f.key || '—' }}</span>
        </span>
        <button
          class="outline-item-delete-btn"
          :title="t('common.delete')"
          @click.stop="remove(f.id)"
        >
          <el-icon><Delete /></el-icon>
        </button>
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n';
import * as ElIcons from '@element-plus/icons-vue';
import { Delete } from '@element-plus/icons-vue';
import { useFormDesignStore } from '@/stores/formDesign';
import { FIELD_TYPE_META } from '@/api/formDesign';

const store = useFormDesignStore();
const { t } = useI18n();

function select(id: string) { store.select(id); }
function remove(id: string) { store.removeField(id); }

function resolveIcon(name?: string) {
  if (!name) return ElIcons.Grid;
  return (ElIcons as Record<string, unknown>)[name] as any || ElIcons.Grid;
}
</script>

<style scoped>
.form-outline {
  width: 100%;
  background: var(--color-bg-elev);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.outline-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px;
  border-bottom: 1px solid var(--color-border-soft);
}
.outline-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.outline-title::before {
  content: '';
  display: inline-block;
  width: 4px;
  height: 14px;
  background: var(--color-primary);
  border-radius: 2px;
}
.outline-count {
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--color-bg);
  border: 1px solid var(--color-border);
}
.outline-empty {
  padding: 24px 16px;
  text-align: center;
  font-size: 12px;
}
.outline-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 4px 6px 12px;
}

.outline-item-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: var(--radius-xs);
  cursor: pointer;
  transition: background 0.1s;
  position: relative;
}
.outline-item-row:hover { background: var(--color-primary-soft); }
.outline-item-row.active {
  background: var(--color-primary-soft);
  color: var(--color-primary);
}
.outline-item-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  font-size: 14px;
  color: var(--color-text-muted);
}
.outline-item-row.active .outline-item-icon { color: var(--color-primary); }
.outline-item-text {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  font-size: 12px;
}
.outline-item-label {
  color: var(--color-text);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.outline-item-required {
  color: var(--color-danger);
  font-weight: 600;
}
.outline-item-key {
  font-family: var(--font-mono);
  font-size: 10px;
  opacity: 0.7;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.outline-item-delete-btn {
  border: none;
  background: transparent;
  cursor: pointer;
  padding: 2px;
  border-radius: 2px;
  font-size: 12px;
  color: var(--color-text-muted);
  opacity: 0;
  transition: opacity 0.1s, color 0.1s;
}
.outline-item-row:hover .outline-item-delete-btn,
.outline-item-row.active .outline-item-delete-btn { opacity: 1; }
.outline-item-delete-btn:hover {
  color: var(--color-danger);
  background: rgba(220, 38, 38, 0.08);
}
</style>