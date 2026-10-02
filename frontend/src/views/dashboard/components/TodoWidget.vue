<template>
  <div class="todo-widget">
    <div class="tw-header">
      <div class="tw-title">
        <el-icon class="tw-icon"><List /></el-icon>
        <span class="tw-name">{{ t('todo.dashboard.title') }}</span>
        <span class="tw-count">({{ items.length }})</span>
      </div>
      <el-button v-if="auth.hasPerm('todo:task:view')" type="primary" link @click="goList">
        {{ t('todo.dashboard.viewAll') }} →
      </el-button>
    </div>

    <div v-if="loading" class="tw-loading muted small">{{ t('common.loading') }}</div>

    <ul v-else-if="items.length > 0" class="tw-list">
      <li v-for="row in items" :key="row.id" class="tw-item">
        <el-checkbox
          :model-value="false"
          :disabled="!auth.hasPerm('todo:task:update')"
          @change="() => completeOne(row.id)"
        />
        <div class="tw-body">
          <div class="tw-row1">
            <span class="tw-title-text" :title="row.title">{{ truncate(row.title, 36) }}</span>
            <span class="prio-tag" :class="`prio-tag-${row.priority}`">
              {{ t(`todo.priority.${(row.priority || 'NONE').toLowerCase()}`) }}
            </span>
            <span class="due" :class="dueClass(row)">{{ dueLabel(row) }}</span>
            <span v-if="row.recurrence !== 'NONE'" class="recurrence" :title="t(`todo.recurrence.${(row.recurrence || 'NONE').toLowerCase()}`)">
              <el-icon><Refresh /></el-icon>
            </span>
          </div>
        </div>
      </li>
    </ul>

    <div v-else class="tw-empty">
      <el-icon class="empty-icon"><MagicStick /></el-icon>
      <div class="empty-text">
        <div class="empty-title">{{ t('todo.dashboard.empty') }}</div>
        <div class="empty-desc muted small">{{ t('todo.dashboard.emptyDesc') }}</div>
      </div>
    </div>

    <div v-if="!loading && items.length >= 10" class="tw-more muted small">
      {{ t('todo.dashboard.moreCount', { n: '+' }) }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { List, Refresh, MagicStick } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { todoApi, type TodoDashboardItem } from '@/api/todo';

const { t } = useI18n();
const router = useRouter();
const auth = useAuthStore();

const items = ref<TodoDashboardItem[]>([]);
const loading = ref(false);

async function reload() {
  if (!auth.hasPerm('todo:task:view')) {
    items.value = [];
    return;
  }
  loading.value = true;
  try {
    items.value = await todoApi.dashboard(10);
  } catch {
    items.value = [];
  } finally {
    loading.value = false;
  }
}

async function completeOne(id: number) {
  try {
    const nextId = await todoApi.complete(id);
    items.value = items.value.filter((x) => x.id !== id);
    ElMessage.success(nextId ? t('todo.recurrenceSpawned') : t('todo.completed'));
  } catch {
    /* interceptor 已弹错 */
  }
}

function goList() {
  router.push('/todo');
}

function truncate(s: string, n: number) {
  return s.length > n ? s.slice(0, n - 1) + '…' : s;
}

function dueClass(row: TodoDashboardItem): string {
  if (!row.dueAt) return 'due-none';
  const d = new Date(row.dueAt).getTime();
  if (d < Date.now()) return 'due-overdue';
  const sameDay = new Date(row.dueAt).toDateString() === new Date().toDateString();
  return sameDay ? 'due-today' : 'due-future';
}

function dueLabel(row: TodoDashboardItem): string {
  if (!row.dueAt) return t('todo.dashboard.dueNoDate');
  const target = new Date(row.dueAt);
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const targetDay = new Date(target.getFullYear(), target.getMonth(), target.getDate());
  const dayDiff = Math.round((targetDay.getTime() - today.getTime()) / 86400000);
  if (dayDiff < 0) {
    const n = Math.abs(dayDiff);
    return t('todo.dashboard.overdueDays', { n });
  }
  if (dayDiff === 0) return t('todo.dashboard.dueToday');
  if (dayDiff === 1) return t('todo.dashboard.dueTomorrow');
  if (dayDiff <= 7) return t('todo.dashboard.dueThisWeek');
  const pad = (n: number) => n.toString().padStart(2, '0');
  return t('todo.dashboard.dueFuture', {
    date: `${target.getFullYear()}-${pad(target.getMonth() + 1)}-${pad(target.getDate())}`,
  });
}

onMounted(reload);
</script>

<style scoped>
.todo-widget {
  background: var(--color-bg-elev);
  border: 1px solid var(--color-border-soft);
  border-radius: 14px;
  padding: 18px 20px 14px;
  margin-bottom: 24px;
  transition: box-shadow 0.2s ease;
}
.todo-widget:hover { box-shadow: 0 6px 18px rgba(15, 23, 42, 0.06); }

.tw-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.tw-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text);
}
.tw-icon { color: var(--color-primary); font-size: 18px; }
.tw-count { color: var(--color-text-muted); font-weight: 400; margin-left: 2px; }

.tw-loading { padding: 16px 0; text-align: center; }

.tw-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 360px;
  overflow-y: auto;
}
.tw-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 4px;
  border-radius: 8px;
  transition: background 0.15s ease;
}
.tw-item:hover { background: var(--color-primary-soft); }

.tw-body { flex: 1; min-width: 0; }
.tw-row1 {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.tw-title-text {
  color: var(--color-text);
  font-weight: 500;
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.prio-tag {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 8px;
  font-size: 11px;
  font-weight: 500;
  border: 1px solid transparent;
  flex-shrink: 0;
}
.prio-tag-HIGH   { background: var(--color-danger-soft); color: var(--color-danger); border-color: var(--color-danger-soft); }
.prio-tag-MEDIUM { background: var(--color-warning-soft); color: var(--color-warning); border-color: var(--color-warning-soft); }
.prio-tag-LOW    { background: var(--color-primary-soft); color: var(--color-primary); border-color: var(--color-primary-soft); }
.prio-tag-NONE   { background: var(--color-bg); color: var(--color-text-muted); border-color: var(--color-border-soft); }

.due {
  font-size: 12px;
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}
.due-overdue { color: var(--color-danger); font-weight: 600; }
.due-today   { color: #d97706; font-weight: 600; }
.due-future  { color: var(--color-text-muted); }
.due-none    { color: var(--color-text-muted); font-style: italic; }

.recurrence {
  display: inline-flex;
  align-items: center;
  color: var(--color-primary);
  flex-shrink: 0;
}

.tw-empty {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 24px 8px;
  text-align: left;
}
.empty-icon {
  font-size: 32px;
  color: #10b981;
  flex-shrink: 0;
}
.empty-text { flex: 1; }
.empty-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: 2px;
}
.empty-desc { font-size: 12px; }

.tw-more {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--color-border-soft);
  text-align: center;
}

.muted { color: var(--color-text-muted); }
.small { font-size: 12px; }
</style>
