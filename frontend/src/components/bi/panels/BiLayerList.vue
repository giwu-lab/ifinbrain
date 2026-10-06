<script setup lang="ts">
/** 左栏 Tab2：图层。按 z 倒序展示（顶层在前），支持选中 / 显隐 / 锁定 / 上下移 / 删除。 */
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { Top, Bottom, Delete } from '@element-plus/icons-vue';
import type { BiWidget } from '@/api/bi';
import { WIDGET_META } from '@/utils/bi/layout';

const props = defineProps<{
  widgets: BiWidget[];
  selectedId?: string | null;
}>();

const emit = defineEmits<{
  (e: 'select', id: string): void;
  (e: 'patch', id: string, patch: Partial<BiWidget>): void;
  (e: 'move', id: string, dir: 1 | -1): void;
  (e: 'remove', id: string): void;
}>();

const { t } = useI18n();

/** 顶层在前 */
const list = computed(() => [...props.widgets].sort((a, b) => b.z - a.z));

function labelOf(w: BiWidget) {
  const raw = w.style.title || t(`bi.widget.${w.type}`, WIDGET_META[w.type]?.labelKey || w.type);
  return raw;
}
</script>

<template>
  <div class="bi-layers">
    <div v-if="!list.length" class="empty">{{ t('bi.layers.empty') }}</div>
    <ul v-else class="list">
      <li
        v-for="w in list"
        :key="w.id"
        class="row"
        :class="{ active: w.id === selectedId, muted: w.hidden }"
        @click="emit('select', w.id)"
      >
        <el-icon class="row-icon">
          <component :is="WIDGET_META[w.type]?.icon || 'Grid'" />
        </el-icon>
        <span class="row-name" :title="labelOf(w)">{{ labelOf(w) }}</span>

        <span class="row-ops" @click.stop>
          <el-tooltip :content="t('bi.layers.moveUp')" placement="top">
            <el-button size="small" link :icon="Top" @click="emit('move', w.id, 1)" />
          </el-tooltip>
          <el-tooltip :content="t('bi.layers.moveDown')" placement="top">
            <el-button size="small" link :icon="Bottom" @click="emit('move', w.id, -1)" />
          </el-tooltip>
          <el-tooltip :content="t('bi.layers.toggleVisible')" placement="top">
            <el-button
              size="small" link
              :icon="w.hidden ? 'Hide' : 'View'"
              @click="emit('patch', w.id, { hidden: !w.hidden })"
            />
          </el-tooltip>
          <el-tooltip :content="t('bi.layers.toggleLock')" placement="top">
            <el-button
              size="small" link
              :icon="w.locked ? 'Lock' : 'Unlock'"
              @click="emit('patch', w.id, { locked: !w.locked })"
            />
          </el-tooltip>
          <el-tooltip :content="t('common.delete')" placement="top">
            <el-button size="small" link type="danger" :icon="Delete" @click="emit('remove', w.id)" />
          </el-tooltip>
        </span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.bi-layers {
  padding: 8px;
  height: 100%;
  overflow: auto;
}
.empty {
  padding: 24px 8px;
  text-align: center;
  color: var(--color-text-muted);
  font-size: 12px;
}
.list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 6px;
  border-radius: 5px;
  cursor: pointer;
  border: 1px solid transparent;
}
.row:hover {
  background: var(--color-bg);
}
.row.active {
  background: var(--color-primary-soft);
  border-color: var(--color-primary);
}
.row.muted .row-name {
  color: var(--color-text-muted);
  text-decoration: line-through;
}
.row-icon {
  font-size: 14px;
  color: var(--color-text-secondary);
  flex: 0 0 auto;
}
.row-name {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 12px;
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-ops {
  display: flex;
  gap: 0;
  flex: 0 0 auto;
  opacity: 0;
}
.row:hover .row-ops,
.row.active .row-ops {
  opacity: 1;
}
</style>