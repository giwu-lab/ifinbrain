<template>
  <aside class="form-designer-palette">
    <div class="palette-header">
      <span class="palette-title">{{ t('formDesigner.palette') }}</span>
    </div>
    <div class="palette-search">
      <el-input
        v-model="search"
        :placeholder="t('formDesigner.paletteSearch')"
        size="small"
        clearable
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
    </div>

    <div class="palette-scroll">
      <el-collapse v-model="activeNames" class="sidebar-collapse">
        <el-collapse-item
          v-for="(items, group) in grouped"
          :key="group"
          :name="group"
          :title="t(`formDesigner.cat.${group}`)"
        >
          <div class="palette-grid">
            <button
              v-for="item in items"
              :key="item.type"
              class="component-item"
              :title="t('formDesigner.paletteHint')"
              @click="onAdd(item.type)"
            >
              <el-icon class="component-icon"><component :is="resolveIcon(item.icon)" /></el-icon>
              <span class="component-label">{{ typeLabel(item.type) }}</span>
            </button>
          </div>
        </el-collapse-item>
      </el-collapse>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import * as ElIcons from '@element-plus/icons-vue';
import { Search } from '@element-plus/icons-vue';
import { FIELD_TYPE_META, FIELD_TYPE_LIST, type FieldType } from '@/api/formDesign';

const emit = defineEmits<{ add: [type: FieldType] }>();
const { t } = useI18n();

const search = ref('');
const activeNames = ref<string[]>([]);

const grouped = computed(() => {
  const g: Record<string, { type: FieldType; icon: string }[]> = {};
  const kw = search.value.trim().toLowerCase();
  for (const type of FIELD_TYPE_LIST) {
    const meta = FIELD_TYPE_META[type];
    // 匹配当前语言的显示名 + 枚举名（RICH_TEXT 这类写法仍能搜到）
    if (kw && !(typeLabel(type).toLowerCase().includes(kw) || type.toLowerCase().includes(kw))) continue;
    if (!g[meta.category]) g[meta.category] = [];
    g[meta.category].push({ type, icon: meta.icon });
  }
  // 初始化默认展开所有分组
  if (activeNames.value.length === 0) {
    activeNames.value = Object.keys(g);
  }
  return g;
});

/** 字段类型显示名 —— 与 FormCanvas / FormFieldProps 走同一组 i18n 键 */
function typeLabel(type: FieldType): string {
  return t(`formDesigner.type.${type}`);
}

function onAdd(type: FieldType) {
  emit('add', type);
}

function resolveIcon(name?: string) {
  if (!name) return ElIcons.Grid;
  return (ElIcons as Record<string, unknown>)[name] as any || ElIcons.Grid;
}
</script>

<style scoped>
.form-designer-palette {
  width: 100%;
  background: var(--color-bg-elev);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.palette-header {
  padding: 12px 12px 8px;
  border-bottom: 1px solid var(--color-border-soft);
}
.palette-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.palette-title::before {
  content: '';
  display: inline-block;
  width: 4px;
  height: 14px;
  background: var(--color-primary);
  border-radius: 2px;
}
.palette-search { padding: 8px 12px; border-bottom: 1px solid var(--color-border-soft); }
.palette-scroll { flex: 1; overflow-y: auto; }

:deep(.sidebar-collapse) { border: none; }
:deep(.sidebar-collapse .el-collapse-item__header) {
  font-weight: 700;
  font-size: 12px;
  padding: 10px 16px !important;
  background: transparent;
  border-bottom: 1px solid var(--color-border-soft);
}
:deep(.sidebar-collapse .el-collapse-item__header:hover) {
  background: var(--color-primary-soft);
}
:deep(.sidebar-collapse .el-collapse-item__content) {
  padding: 6px 12px 14px !important;
}

.palette-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.component-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  gap: 6px;
  padding: 10px 6px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: var(--color-bg);
  cursor: grab;
  transition: all 0.15s;
  font-size: 11px;
  color: var(--color-text-secondary);
}
.component-item:active { transform: scale(0.95); }
.component-item:hover {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
}
.component-item:hover .component-icon { color: var(--color-primary); }
.component-icon { font-size: 18px; color: var(--color-text-muted); transition: color 0.15s; }
.component-label { text-align: center; font-size: 11px; }
</style>