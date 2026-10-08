<script setup lang="ts">
/**
 * 底部状态栏 —— Typora / MarkText 的那一行：当前章节 + 字数统计，右侧是保存态。
 *
 * 保存态刻意放这里而不是顶栏：顶栏已经有 ● 未保存标记了，
 * 两处都写会重复，而状态栏本来就是「我现在处于什么状态」的地方。
 */
import { useI18n } from 'vue-i18n';

export type SaveStatus = 'saved' | 'saving' | 'dirty' | 'error';

export interface WriterStats {
  chars: number;
  words: number;
  lines: number;
  heading: string;
}

defineProps<{
  stats?: WriterStats | null;
  status: SaveStatus;
  errorText?: string;
}>();

const { t } = useI18n();

function statusText(status: SaveStatus, errorText?: string) {
  if (status === 'error') return errorText || t('note.saveFailed');
  if (status === 'saving') return t('note.saving');
  if (status === 'dirty') return t('note.dirty');
  return t('note.saved');
}
</script>

<template>
  <div class="note-statusbar">
    <div class="bar-left">
      <span v-if="stats?.heading" class="bar-heading" :title="stats.heading">{{ stats.heading }}</span>
      <span v-if="stats" class="bar-meta tabular">
        {{ stats.words }} {{ t('note.unitWords') }}
        <span class="bar-dot">·</span>
        {{ stats.chars }} {{ t('note.unitChars') }}
        <span class="bar-dot">·</span>
        {{ stats.lines }} {{ t('note.unitLines') }}
      </span>
    </div>
    <div class="bar-right" :class="`is-${status}`">{{ statusText(status, errorText) }}</div>
  </div>
</template>

<style scoped>
.note-statusbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 30px;
  padding: 0 16px;
  border-top: 1px solid var(--color-border-soft);
  background: var(--color-bg);
  font-size: 12px;
  color: var(--color-text-muted);
  flex-shrink: 0;
}
.bar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.bar-heading {
  max-width: 40%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-text-secondary);
}
.bar-meta { flex: 0 0 auto; }
.bar-dot { margin: 0 4px; opacity: 0.6; }
.bar-right { flex: 0 0 auto; }
.bar-right.is-saved { color: var(--color-text-muted); }
.bar-right.is-saving { color: var(--color-text-secondary); }
.bar-right.is-dirty { color: var(--color-warning); }
.bar-right.is-error { color: var(--color-danger); }
</style>