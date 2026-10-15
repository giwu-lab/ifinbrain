<script setup lang="ts">
/**
 * 笔记管理 —— **列表页**。
 *
 * 只负责筛选 / 列表 / 行内操作；正文编辑在独立路由 `/documents/notes/{id}`
 * （同 data-mgmt 查询编辑的拆分方式：编辑器要占满宽度，和列表挤一屏没法用）。
 */
import { onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import { noteApi, type NoteItem, type NoteListScope, type NoteStats } from '@/api/note';
import { useAuthStore } from '@/stores/auth';
import NoteList from './components/NoteList.vue';

const { t } = useI18n();
const router = useRouter();
const auth = useAuthStore();

const rows = ref<NoteItem[]>([]);
const stats = ref<NoteStats | null>(null);
const loading = ref(false);
const total = ref(0);
const page = ref(1);
const size = ref(20);
const scope = ref<NoteListScope>('all');
const keyword = ref('');

async function loadStats() {
  try { stats.value = await noteApi.stats(); } catch { stats.value = null; }
}

async function load() {
  loading.value = true;
  try {
    const res = await noteApi.list({
      page: page.value,
      size: size.value,
      scope: scope.value,
      keyword: keyword.value || undefined,
    });
    rows.value = res.content ?? [];
    total.value = res.totalElements ?? 0;
  } finally {
    loading.value = false;
  }
}

async function reload() {
  await Promise.all([load(), loadStats()]);
}

onMounted(reload);

watch(scope, () => { page.value = 1; void load(); });

let searchTimer: ReturnType<typeof setTimeout> | null = null;
watch(keyword, () => {
  if (searchTimer) clearTimeout(searchTimer);
  searchTimer = setTimeout(() => { page.value = 1; void load(); }, 350);
});

watch(page, () => void load());

function open(row: NoteItem) {
  void router.push(`/documents/notes/${row.id}`);
}

function create() {
  void router.push('/documents/notes/new');
}

function onScopeChange(s: NoteListScope) {
  scope.value = s;
}

function onKeywordChange(v: string) {
  keyword.value = v;
}

function onPageChange(p: number) {
  page.value = p;
}

/** 切换每页条数：页码必须重置回第 1，否则会停在越界页码上拿到空列表 */
function onSizeChange() {
  page.value = 1;
}

async function onCmd(cmd: string, row: NoteItem) {
  try {
    if (cmd === 'pin' || cmd === 'unpin') {
      await noteApi.pin(row.id, cmd === 'pin');
    } else if (cmd === 'archive' || cmd === 'unarchive') {
      await noteApi.archive(row.id, cmd === 'archive');
    } else if (cmd === 'delete') {
      await ElMessageBox.confirm(
        t('note.confirmDelete', { name: row.title }),
        t('common.confirmDelete'),
        { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') },
      );
      await noteApi.remove(row.id);
      ElMessage.success(t('common.deleted'));
    }
    await reload();
  } catch {
    /* 用户取消 */
  }
}
</script>

<template>
  <div class="page-container fade-in-up note-page">
    <div class="table-wrap note-table-wrap">
      <div class="table-actions">
        <el-button v-if="auth.hasPerm('note:create')" type="primary" :icon="Plus" @click="create">
          {{ t('note.create') }}
        </el-button>
      </div>
      <NoteList
        :rows="rows"
        :loading="loading"
        :scope="scope"
        :stats="stats"
        @select="open"
        @cmd="onCmd"
        @scope="onScopeChange"
        @keyword="onKeywordChange"
      />
      <div v-if="total > size" class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10,20,50,100]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.note-page .header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

/* 列表区：1px 边框 plain div，不用 el-card（§7）。
 * overflow 保持默认，让表格随页面滚动，不做内层滚动容器。 */
.note-table-wrap {
  margin-top: 0;
}
.pager {
  padding-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>
