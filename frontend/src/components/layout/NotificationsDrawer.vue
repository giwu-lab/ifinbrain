<template>
  <el-drawer
    :model-value="modelValue"
    direction="rtl"
    size="380px"
    :with-header="false"
    :modal="true"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <div class="nd-root">
      <header class="nd-header">
        <div class="nd-title">
          <el-icon><BellFilled /></el-icon>
          <h3>{{ t('header.notifCenter.title') }}</h3>
        </div>
        <el-button text class="nd-close" @click="emit('update:modelValue', false)">
          <el-icon><Close /></el-icon>
        </el-button>
      </header>

      <el-tabs v-model="activeTab" class="nd-tabs" stretch>
        <!-- ===== 消息：流程待审批 ===== -->
        <el-tab-pane :name="'messages'">
          <template #label>
            <span class="nd-tab-label">
              {{ t('header.notifCenter.tabMessages') }}
              <el-badge v-if="flowTasks.length > 0" :value="flowTasks.length" class="nd-badge" />
            </span>
          </template>

          <div v-if="loadingFlow" class="nd-loading">
            <el-icon class="is-loading"><Loading /></el-icon>
            {{ t('common.loading') }}
          </div>

          <ul v-else-if="flowTasks.length > 0" class="nd-list">
            <li
              v-for="task in flowTasks"
              :key="task.id"
              class="nd-item nd-item-flow"
              @click="goFlow(task)"
            >
              <div class="nd-item-icon"><el-icon><Promotion /></el-icon></div>
              <div class="nd-item-body">
                <div class="nd-item-title">{{ task.flowName }}</div>
                <div class="nd-item-meta">
                  <span class="nd-pill nd-pill-flow">{{ task.nodeLabel }}</span>
                  <span class="nd-meta-text muted">{{ task.startedBy }}</span>
                </div>
                <div class="nd-item-foot muted mono-time">{{ formatDate(task.createdAt) }}</div>
              </div>
            </li>
          </ul>

          <div v-else class="nd-empty">
            <el-icon class="empty-icon"><ChatLineRound /></el-icon>
            <div class="empty-title">{{ t('header.notifCenter.flowEmpty') }}</div>
            <div class="empty-desc muted">{{ t('header.notifCenter.flowEmptyDesc') }}</div>
          </div>

          <footer v-if="flowTasks.length > 0" class="nd-foot">
            <el-button text type="primary" @click="goFlowRuntime">
              {{ t('header.notifCenter.viewAll') }} →
            </el-button>
          </footer>
        </el-tab-pane>

        <!-- ===== 待办：个人任务 ===== -->
        <el-tab-pane :name="'todos'">
          <template #label>
            <span class="nd-tab-label">
              {{ t('header.notifCenter.tabTodos') }}
              <el-badge v-if="todos.length > 0" :value="todos.length" class="nd-badge" />
            </span>
          </template>

          <div v-if="loadingTodo" class="nd-loading">
            <el-icon class="is-loading"><Loading /></el-icon>
            {{ t('common.loading') }}
          </div>

          <ul v-else-if="todos.length > 0" class="nd-list">
            <li
              v-for="task in todos"
              :key="task.id"
              class="nd-item nd-item-todo"
              @click="goTodo"
            >
              <div class="nd-item-icon"><el-icon><List /></el-icon></div>
              <div class="nd-item-body">
                <div class="nd-item-title">{{ task.title }}</div>
                <div class="nd-item-meta">
                  <span class="nd-pill" :class="`prio-${task.priority}`">
                    {{ t(`todo.priority.${(task.priority || 'NONE').toLowerCase()}`) }}
                  </span>
                  <span v-if="task.dueAt" class="nd-meta-text" :class="{ overdue: dueOverdue(task.dueAt) }">
                    {{ dueLabel(task.dueAt) }}
                  </span>
                </div>
              </div>
            </li>
          </ul>

          <div v-else class="nd-empty">
            <el-icon class="empty-icon"><MagicStick /></el-icon>
            <div class="empty-title">{{ t('header.notifCenter.todoEmpty') }}</div>
            <div class="empty-desc muted">{{ t('header.notifCenter.todoEmptyDesc') }}</div>
          </div>

          <footer v-if="todos.length > 0" class="nd-foot">
            <el-button text type="primary" @click="goTodo">
              {{ t('header.notifCenter.viewAll') }} →
            </el-button>
          </footer>
        </el-tab-pane>

        <!-- ===== 公告 ===== -->
        <el-tab-pane :name="'announcements'">
          <template #label>
            <span class="nd-tab-label">{{ t('header.notifCenter.tabAnnouncements') }}</span>
          </template>

          <div class="nd-empty">
            <el-icon class="empty-icon"><Notification /></el-icon>
            <div class="empty-title">{{ t('header.notifCenter.announcementEmpty') }}</div>
            <div class="empty-desc muted">{{ t('header.notifCenter.announcementEmptyDesc') }}</div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import {
  Close, Loading, Promotion, ChatLineRound, List, MagicStick,
  Notification, BellFilled,
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { todoApi, type TodoDashboardItem } from '@/api/todo';
import { flowTaskApi, type FlowTaskItem } from '@/api/flowTask';
import { formatDateTime } from '@/utils/time';

const props = defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>();

const { t } = useI18n();
const router = useRouter();
const auth = useAuthStore();

const activeTab = ref<'messages' | 'todos' | 'announcements'>('messages');
const flowTasks = ref<FlowTaskItem[]>([]);
const todos = ref<TodoDashboardItem[]>([]);
const loadingFlow = ref(false);
const loadingTodo = ref(false);
let loadedOnce = false;

/** 抽屉首次打开时：消息 + 待办都预加载；切换 tab 时如果未加载过则触发 */
watch(
  () => props.modelValue,
  (open) => {
    if (!open) return;
    if (!loadedOnce) {
      loadedOnce = true;
      loadFlow();
      loadTodos();
    }
  }
);

watch(
  () => activeTab.value,
  (tab) => {
    if (!loadedOnce) return;
    if (tab === 'messages' && flowTasks.value.length === 0 && !loadingFlow.value) loadFlow();
    if (tab === 'todos' && todos.value.length === 0 && !loadingTodo.value) loadTodos();
  }
);

async function loadFlow() {
  if (!auth.hasPerm('design:flow:view')) { flowTasks.value = []; return; }
  loadingFlow.value = true;
  try {
    flowTasks.value = (await flowTaskApi.pending()).slice(0, 10);
  } catch (e: any) {
    ElMessage.warning(e?.message || t('header.notifCenter.flowLoadFail'));
    flowTasks.value = [];
  } finally {
    loadingFlow.value = false;
  }
}

async function loadTodos() {
  if (!auth.hasPerm('todo:task:view')) { todos.value = []; return; }
  loadingTodo.value = true;
  try {
    todos.value = await todoApi.dashboard(10);
  } catch (e: any) {
    ElMessage.warning(e?.message || t('header.notifCenter.todoLoadFail'));
    todos.value = [];
  } finally {
    loadingTodo.value = false;
  }
}

/** 抽屉从关闭 → 打开时预加载消息 + 待办（按权限） */
watch(
  () => /* modelValue */ 0,
  () => {}
);

function goFlow(task: FlowTaskItem) {
  emit('update:modelValue', false);
  router.push({ path: '/design/flow-runtime', query: { taskId: String(task.id) } });
}

function goFlowRuntime() {
  emit('update:modelValue', false);
  router.push(`/design/flow-runtime`);
}

function goTodo() {
  emit('update:modelValue', false);
  router.push('/todo');
}

function formatDate(s: string) { return formatDateTime(s); }

function dueLabel(due: string) {
  const ms = new Date(due).getTime() - Date.now();
  if (ms < 0) {
    const days = Math.ceil(-ms / 86400000);
    return t('todo.dashboard.overdueDays', { n: days });
  }
  if (ms < 86400000) return t('todo.dashboard.dueToday');
  return formatDateTime(due);
}

function dueOverdue(due: string) {
  return new Date(due).getTime() < Date.now();
}
</script>

<style scoped>
.nd-root { display: flex; flex-direction: column; height: 100%; background: var(--color-bg-elev); }

.nd-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--color-border-soft);
}
.nd-title { display: flex; align-items: center; gap: 8px; color: var(--color-primary); }
.nd-title h3 { margin: 0; font-size: 16px; font-weight: 600; color: var(--color-text); }
.nd-close { color: var(--color-text-muted); }

.nd-tabs { flex: 1; overflow: hidden; }
.nd-tabs :deep(.el-tabs__header) { margin: 0; padding: 0 16px; border-bottom: 1px solid var(--color-border-soft); }
.nd-tabs :deep(.el-tabs__content) { padding: 0 12px; height: calc(100% - 50px); overflow-y: auto; }
.nd-tabs :deep(.el-tab-pane) { padding: 0; }

.nd-tab-label { display: inline-flex; align-items: center; gap: 6px; font-size: 14px; }
.nd-badge :deep(.el-badge__content) { transform: translateY(-50%) scale(0.85); }

.nd-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px 0;
  color: var(--color-text-secondary);
  font-size: 13px;
}

.nd-list { list-style: none; padding: 0; margin: 0; }
.nd-item {
  display: flex;
  gap: 12px;
  padding: 12px 8px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s ease;
  border-bottom: 1px solid var(--color-border-soft);
}
.nd-item:hover { background: var(--color-primary-soft); }
.nd-item:last-child { border-bottom: none; }

.nd-item-icon {
  width: 32px; height: 32px;
  border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
  background: var(--color-primary-softer);
  color: var(--color-primary);
  font-size: 16px;
}
.nd-item-flow .nd-item-icon { background: var(--color-warning-soft); color: var(--color-warning); }
.nd-item-todo .nd-item-icon { background: var(--color-primary-softer); color: var(--color-primary); }

.nd-item-body { flex: 1; min-width: 0; }
.nd-item-title {
  font-size: 13.5px;
  font-weight: 500;
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.nd-item-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
}
.nd-meta-text { color: var(--color-text-secondary); }
.nd-meta-text.overdue { color: var(--color-danger); }
.nd-item-foot { font-size: 11px; margin-top: 4px; }

.nd-pill {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 10px;
  background: var(--color-bg);
  color: var(--color-text-secondary);
  font-size: 11px;
  line-height: 18px;
}
.nd-pill-flow { background: var(--color-warning-soft); color: var(--color-warning); }
.nd-pill.prio-HIGH { background: var(--color-danger-soft); color: var(--color-danger); }
.nd-pill.prio-MEDIUM { background: var(--color-warning-soft); color: var(--color-warning); }
.nd-pill.prio-LOW { background: var(--color-bg); color: var(--color-text-muted); }
.nd-pill.prio-NONE { background: var(--color-bg); color: var(--color-text-muted); }

.nd-foot {
  text-align: center;
  padding: 8px 0;
  border-top: 1px solid var(--color-border-soft);
  margin-top: 8px;
}

.nd-empty {
  text-align: center;
  padding: 60px 20px;
  color: var(--color-text-secondary);
}
.nd-empty .empty-icon { font-size: 36px; color: var(--color-text-muted); margin-bottom: 12px; }
.nd-empty .empty-title { font-size: 14px; color: var(--color-text); margin-bottom: 4px; }
.nd-empty .empty-desc { font-size: 12px; }
</style>

<style>
.el-drawer .el-drawer__body { padding: 0 !important; }
.el-drawer__header { display: none; }
</style>