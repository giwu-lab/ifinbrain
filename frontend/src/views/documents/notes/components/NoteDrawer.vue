<script setup lang="ts">
/**
 * 档案库文件列表页上的「笔记」抽屉。
 *
 * 规格对齐档案预览抽屉（el-drawer 80% rtl，AGENTS §10.2）——
 * 但这里**不**开 destroy-on-close：它是列表而非预览，切库时数据要重新拉，
 * 组件保持挂载即可，没必要反复销毁。
 *
 * 只读列表 + 「新建」+「在笔记管理中打开」，不做就地编辑 ——
 * 就地编辑要重放整套编辑器与冲突处理，而抽屉是列表页的附属入口，不值得。
 */
import { ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRouter } from 'vue-router';
import { Plus } from '@element-plus/icons-vue';
import { noteApi, type NoteItem } from '@/api/note';
import { useAuthStore } from '@/stores/auth';

const props = defineProps<{ categoryId: number | null; categoryName?: string }>();
const visible = defineModel<boolean>({ default: false });

const { t } = useI18n();
const router = useRouter();
const auth = useAuthStore();

const rows = ref<NoteItem[]>([]);
const loading = ref(false);

watch([visible, () => props.categoryId], async ([v, cid]) => {
  if (!v || !cid) return;
  loading.value = true;
  try {
    // 抽屉里默认只看挂在本档案库上的笔记（LIBRARY scope）
    const res = await noteApi.list({ categoryId: cid, size: 50, scope: 'library' });
    rows.value = res.content ?? [];
  } catch {
    rows.value = [];
  } finally {
    loading.value = false;
  }
});

async function createHere() {
  if (!props.categoryId) return;
  visible.value = false;
  // 带 categoryId 过去：编辑页据此把草稿预设为「挂该档案库」，省一次选范围
  void router.push({ path: '/documents/notes/new', query: { categoryId: String(props.categoryId) } });
}

function openInManager() {
  void router.push({ path: '/documents/notes' });
}

function timeText(s?: string) {
  return s ? s.replace('T', ' ').slice(0, 16) : '';
}
</script>

<template>
  <el-drawer v-model="visible" :title="t('note.drawerTitle')" size="80%" direction="rtl">
    <div class="note-drawer">
      <div class="drawer-head">
        <span class="drawer-sub">
          {{ categoryName }}<span v-if="rows.length" class="muted"> · {{ rows.length }}</span>
        </span>
        <div class="header-actions">
          <el-button size="small" @click="openInManager">{{ t('note.openInManager') }}</el-button>
          <el-button
            v-if="auth.hasPerm('note:create')"
            size="small"
            type="primary"
            :icon="Plus"
            @click="createHere"
          >{{ t('note.create') }}</el-button>
        </div>
      </div>

      <div class="table-wrap drawer-table-wrap">
        <el-table :data="rows" v-loading="loading" border stripe size="small">
          <el-table-column :label="t('note.title')" min-width="280" show-overflow-tooltip>
            <template #default="{ row }">
              <div class="cell-title">{{ row.title }}</div>
              <div v-if="row.excerpt" class="cell-excerpt">{{ row.excerpt }}</div>
            </template>
          </el-table-column>
          <el-table-column :label="t('note.scopeLabel')" width="140">
            <template #default="{ row }">
              <span class="mono-scope">
                {{ t(`note.scopeTypes.${row.scopeType}`) }}
                <template v-if="row.scopeDeptName"> · {{ row.scopeDeptName }}</template>
              </span>
            </template>
          </el-table-column>
          <el-table-column :label="t('note.tags')" width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ (row.tags ?? []).join(' · ') || '—' }}</template>
          </el-table-column>
          <el-table-column :label="t('note.author')" width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.ownerName }}</template>
          </el-table-column>
          <el-table-column :label="t('note.lastEditor')" width="150">
            <template #default="{ row }">
              <span class="mono-time">{{ timeText(row.updatedAt) }}</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="drawer-empty">{{ t('note.drawerEmpty') }}</div>
          </template>
        </el-table>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.note-drawer { display: flex; flex-direction: column; gap: 12px; }
.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.drawer-sub { font-size: 14px; color: var(--color-text-secondary); }
.drawer-table-wrap {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
:deep(.drawer-table-wrap th.el-table__cell) {
  position: sticky;
  top: 0;
  z-index: 10;
  background: var(--color-bg) !important;
}
.cell-title { font-weight: 600; color: var(--color-text); }
.cell-excerpt {
  font-size: 12px;
  color: var(--color-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-top: 2px;
}
.mono-scope { font-size: 12px; color: var(--color-text-secondary); }
.drawer-empty {
  padding: 30px 0;
  text-align: center;
  font-size: 13px;
  color: var(--color-text-muted);
}
</style>