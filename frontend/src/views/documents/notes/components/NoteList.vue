<script setup lang="ts">
/**
 * 笔记列表。表格标准按 AGENTS §8：border + stripe + size="small"，
 * 操作列高频图标直显、删除收进 `···`（§8.2）。
 *
 * 范围标记不用 pill 标签（§7 设计原则），改成标题左侧的等宽小字 + 分隔线。
 */
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { Edit, Star, StarFilled, MoreFilled, Delete, Collection, User } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import type { NoteItem, NoteListScope, NoteStats } from '@/api/note';

const props = defineProps<{
  rows: NoteItem[];
  loading?: boolean;
  selectedId?: number | null;
  scope?: NoteListScope;
  stats?: NoteStats | null;
}>();

const emit = defineEmits<{
  (e: 'select', row: NoteItem): void;
  (e: 'cmd', cmd: string, row: NoteItem): void;
  (e: 'scope', scope: NoteListScope): void;
  (e: 'keyword', value: string): void;
}>();

const { t } = useI18n();
const auth = useAuthStore();

const tabs = computed<{ key: NoteListScope; label: string; count?: number }[]>(() => [
  { key: 'all', label: t('note.scope.all') },
  { key: 'mine', label: t('note.scope.mine'), count: props.stats?.mine },
  { key: 'shared', label: t('note.scope.shared'), count: props.stats?.shared },
  { key: 'team', label: t('note.scope.team'), count: props.stats?.team },
  { key: 'archived', label: t('note.scope.archived'), count: props.stats?.archived },
]);

function grantText(row: unknown) {
  const r = row as NoteItem;
  return r.myGrant ? t(`note.grant.${r.myGrant}`) : '';
}

function scopeText(row: unknown) {
  const r = row as NoteItem;
  if (r.scopeType === 'TEAM') return t('note.scopeTypes.TEAM') + (r.scopeDeptName ? ` · ${r.scopeDeptName}` : '');
  if (r.scopeType === 'LIBRARY') return t('note.scopeTypes.LIBRARY') + (r.categoryName ? ` · ${r.categoryName}` : '');
  return t(`note.scopeTypes.${r.scopeType}`);
}

function timeText(s?: string) {
  if (!s) return '';
  return s.replace('T', ' ').slice(5, 16);
}

function onTabClick(key: NoteListScope) {
  emit('scope', key);
}

/** 模板里不能写带 TS 类型的内联箭头函数（会被当 JS 解析），所以都收进 script。
 *  el-table 的作用域插槽把 row 声明为 DefaultRow（= any 的宽松类型），
 *  这里统一用 unknown 收口、在函数体内断言成 NoteItem，避免模板里到处 as。 */
function rowClass({ row }: { row: unknown }) {
  return (row as NoteItem).id === props.selectedId ? 'is-selected' : '';
}

function onRowClick(row: unknown) {
  emit('select', row as NoteItem);
}

function onRowCommand(cmd: string, row: unknown) {
  emit('cmd', cmd, row as NoteItem);
}

function onPin(row: unknown) {
  const r = row as NoteItem;
  emit('cmd', r.pinned ? 'unpin' : 'pin', r);
}

function onArchive(row: unknown) {
  const r = row as NoteItem;
  emit('cmd', r.status === 1 ? 'archive' : 'unarchive', r);
}

function onSearch(value: string) {
  emit('keyword', value);
}
</script>

<template>
  <div class="note-list">
    <div class="list-tabs">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="list-tab"
        :class="{ active: props.scope === tab.key }"
        @click="onTabClick(tab.key)"
      >
        {{ tab.label }}
        <span v-if="tab.count !== undefined && tab.count > 0" class="tab-count tabular">{{ tab.count }}</span>
      </button>
    </div>

    <div class="list-search">
      <el-input
        :model-value="''"
        :placeholder="t('note.searchPlaceholder')"
        clearable
        @update:model-value="onSearch"
      />
    </div>

    <div class="table-wrap list-table-wrap">
      <el-table
        :data="rows"
        v-loading="loading"
        border
        stripe
        size="small"
        :row-class-name="rowClass"
        @row-click="onRowClick"
      >
        <el-table-column :label="t('note.title')" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="title-cell">
              <el-icon v-if="row.pinned" class="pin-icon"><StarFilled /></el-icon>
              <span class="title-text">{{ row.title }}</span>
              <span v-if="row.sharedWithMe" class="grant-text">{{ grantText(row) }}</span>
            </div>
            <div v-if="row.excerpt" class="excerpt-text">{{ row.excerpt }}</div>
          </template>
        </el-table-column>

        <el-table-column :label="t('note.scopeLabel')" width="150">
          <template #default="{ row }">
            <span class="mono-scope">{{ scopeText(row) }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('note.tags')" width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="(row.tags ?? []).length" class="tags-text">{{ (row.tags ?? []).join(' · ') }}</span>
            <span v-else class="muted-text">—</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('note.author')" width="110" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="author-cell">
              <el-icon><User /></el-icon>{{ row.ownerName || row.ownerId }}
            </span>
          </template>
        </el-table-column>

        <el-table-column :label="t('note.lastEditor')" width="110">
          <template #default="{ row }">
            <span class="mono-time">{{ timeText(row.updatedAt) }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('common.actions')" :width="120" align="center" fixed="right">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.canEdit && auth.hasPerm('note:update')"
              :content="row.pinned ? t('note.unpin') : t('note.pin')"
              placement="top"
            >
              <el-button
                size="small"
                link
                :icon="row.pinned ? StarFilled : Star"
                @click.stop="onPin(row)"
              />
            </el-tooltip>
            <el-tooltip
              v-if="row.canEdit && auth.hasPerm('note:update')"
              :content="row.status === 1 ? t('note.archive') : t('note.unarchive')"
              placement="top"
            >
              <el-button
                size="small"
                link
                :icon="Collection"
                @click.stop="onArchive(row)"
              />
            </el-tooltip>
            <el-dropdown
              v-if="row.owner && auth.hasPerm('note:delete')"
              trigger="click"
              @command="(cmd) => onRowCommand(cmd, row)"
            >
              <el-button :icon="MoreFilled" size="small" link @click.stop />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="delete" divided>
                    <span class="danger-text">
                      <el-icon><Delete /></el-icon>{{ t('common.delete') }}
                    </span>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-tooltip
              v-else-if="row.canEdit && auth.hasPerm('note:update')"
              :content="t('common.edit')"
              placement="top"
            >
              <el-button size="small" link :icon="Edit" @click.stop="onRowClick(row)" />
            </el-tooltip>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-cell">
            <div>{{ rows.length === 0 && !loading ? t('note.emptyFiltered') : '' }}</div>
            <div class="empty-hint">{{ t('note.emptyHint') }}</div>
          </div>
        </template>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.note-list {
  display: flex;
  flex-direction: column;
}
.list-tabs {
  display: flex;
  gap: 2px;
  border-bottom: 1px solid var(--color-border-soft);
  margin-bottom: 10px;
}
.list-tab {
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  color: var(--color-text-secondary);
  font-size: 13px;
  padding: 6px 10px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.list-tab:hover { color: var(--color-primary); }
.list-tab.active {
  color: var(--color-primary);
  border-bottom-color: var(--color-primary);
  font-weight: 600;
}
.tab-count {
  font-size: 11px;
  color: var(--color-text-muted);
}
.list-search { margin-bottom: 10px; }
/* 不做内层滚动容器 —— 列表页已把筛选/分页收进边框内，页面自身滚动即可，
 * 内层 overflow 会形成嵌套滚动、并让 th sticky 失效。 */
.list-table-wrap { min-width: 0; }
/* 表头不吸顶 —— <el-table> 内部布局对 position:sticky 极敏感，
 * 把 header-wrapper 设 sticky 会让 tbody 跑到表头上方（documents/list 实测同一结论）。
 * 这里只固定表头区域底色，滚动时不露白。 */
:deep(.list-table-wrap .el-table__header-wrapper),
:deep(.list-table-wrap .el-table__header-wrapper table) {
  background: var(--color-bg);
}
:deep(.list-table-wrap .el-table__row.is-selected) {
  background: var(--color-primary-softer) !important;
}
.title-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.pin-icon { color: var(--color-warning); font-size: 13px; }
.title-text {
  font-weight: 600;
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.grant-text {
  font-size: 11px;
  color: var(--color-info);
  border: 1px solid var(--color-info-soft);
  border-radius: 3px;
  padding: 0 4px;
  flex: 0 0 auto;
}
.excerpt-text {
  font-size: 12px;
  color: var(--color-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-top: 2px;
}
.mono-scope {
  font-size: 12px;
  color: var(--color-text-secondary);
}
.tags-text { font-size: 12px; color: var(--color-text-secondary); }
.muted-text { color: var(--color-text-muted); }
.author-cell {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--color-text-secondary);
}
.empty-cell {
  padding: 18px 0;
  color: var(--color-text-muted);
  font-size: 13px;
}
.empty-hint {
  font-size: 12px;
  margin-top: 4px;
  color: var(--color-text-muted);
  opacity: 0.8;
}
.danger-text { color: var(--color-danger); }
</style>