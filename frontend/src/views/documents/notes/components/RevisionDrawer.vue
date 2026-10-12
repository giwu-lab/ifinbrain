<script setup lang="ts">
/**
 * 版本历史抽屉。回滚 = 服务端一次普通保存（历史不丢，会多出一条快照）。
 */
import { ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox } from 'element-plus';
import { noteApi, type NoteItem, type NoteRevisionItem } from '@/api/note';
import { formatDateTime } from '@/utils/time';
import NoteViewer from './NoteViewer.vue';

const props = defineProps<{ note: NoteItem | null }>();
const emit = defineEmits<{ (e: 'restored', note: NoteItem): void }>();

const { t } = useI18n();
const visible = defineModel<boolean>({ default: false });

const loading = ref(false);
const rows = ref<NoteRevisionItem[]>([]);
const current = ref<NoteRevisionItem | null>(null);

watch(visible, async (v) => {
  if (!v || !props.note) return;
  current.value = null;
  await load();
});

watch(() => props.note?.id, () => { if (visible.value) void load(); });

async function load() {
  if (!props.note) return;
  loading.value = true;
  try {
    rows.value = await noteApi.revisions(props.note.id);
  } finally {
    loading.value = false;
  }
}

async function view(row: NoteRevisionItem) {
  if (!props.note) return;
  current.value = await noteApi.revision(props.note.id, row.revision);
}

async function restore(row: NoteRevisionItem) {
  if (!props.note) return;
  try {
    await ElMessageBox.confirm(
      t('note.restoreConfirm', { n: row.revision }),
      t('note.revisions.title'),
      { type: 'warning', confirmButtonText: t('note.restore'), cancelButtonText: t('common.cancel') },
    );
  } catch { return; }
  const updated = await noteApi.restore(props.note.id, row.revision);
  ElMessage.success(t('note.restored', { n: row.revision }));
  current.value = null;
  visible.value = false;
  emit('restored', updated);
}

/** 时间统一走 utils/time.formatDateTime：yyyy-MM-dd HH:mm:ss（AGENTS §8.3） */
function timeText(s?: string | null) {
  return formatDateTime(s);
}
</script>

<template>
  <el-drawer v-model="visible" :title="t('note.revisions.title')" size="520px" direction="rtl">
    <div v-loading="loading" class="rev-body">
      <div v-if="rows.length === 0" class="rev-empty">{{ t('note.revisions.empty') }}</div>
      <div
        v-for="row in rows"
        :key="row.revision"
        class="rev-row"
        :class="{ active: current?.revision === row.revision }"
      >
        <div class="rev-main">
          <div class="rev-title">{{ t('note.revisions.item', { n: row.revision }) }} · {{ row.title }}</div>
          <div class="rev-meta">
            <span v-if="row.editorName">{{ row.editorName }}</span>
            <span class="mono-time">{{ timeText(row.createdAt) }}</span>
          </div>
        </div>
        <div class="rev-ops">
          <el-button size="small" link @click="view(row)">{{ t('note.revisions.view') }}</el-button>
          <el-button size="small" link @click="restore(row)">{{ t('note.restore') }}</el-button>
        </div>
      </div>

      <template v-if="current">
        <el-divider content-position="left">
          {{ t('note.revisions.item', { n: current.revision }) }}
        </el-divider>
        <div class="rev-preview">
          <NoteViewer :content="current.content" :show-outline="false" />
        </div>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.rev-body { min-height: 200px; }
.rev-empty {
  font-size: 13px;
  color: var(--color-text-muted);
  padding: 20px 0;
  text-align: center;
}
.rev-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--color-border-soft);
  border-left: 2px solid transparent;
}
.rev-row:hover { background: var(--color-primary-softer); }
.rev-row.active { border-left-color: var(--color-primary); background: var(--color-primary-softer); }
.rev-main { min-width: 0; }
.rev-title {
  font-size: 13px;
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rev-meta {
  display: flex;
  gap: 10px;
  font-size: 12px;
  color: var(--color-text-muted);
  margin-top: 2px;
}
.rev-ops { flex: 0 0 auto; }
.rev-preview {
  border: 1px solid var(--color-border-soft);
  border-radius: 6px;
  padding: 12px;
  background: var(--color-bg-elev);
  max-height: 46vh;
  overflow-y: auto;
}
</style>