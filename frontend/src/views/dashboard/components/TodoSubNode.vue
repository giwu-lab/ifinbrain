<script setup lang="ts">
/**
 * 子任务树节点（递归）。父任务卡片默认不渲染它，点「子任务 n/m」才挂载。
 *
 * <p>自己持有展开态和懒加载结果，而不是把整棵树的状态塞进 TodoWidget：
 * 象限里可能同时有十几个父任务，每个都可能展开若干层，集中管理会得到一个
 * 按 id 索引的大对象，而每个节点真正需要的只是「我自己展开了没有」。
 *
 * <p>每层都调同一个 /children 端点，天然支持任意深度 —— 后端只返回直接子级，
 * 孙级要等这一层被点开再去拉。
 */
import { ref, computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { CaretRight } from '@element-plus/icons-vue';
import { todoApi, type TodoTask } from '@/api/todo';

// 递归组件必须能自引用：<script setup> 的隐式自引用按文件名解析，
// 显式 name 兜住 —— 否则模板里的 <TodoSubNode> 会被当成未知组件。
defineOptions({ name: 'TodoSubNode' });

const props = defineProps<{
  task: TodoTask;
  /** 层级缩进深度，0 起；仅用于视觉缩进，不影响数据。 */
  depth?: number;
}>();

/** 子任务被勾选后向上冒泡：父任务的 childDone/canComplete 都随之改变。 */
const emit = defineEmits<{ (e: 'mutated'): void }>();

const { t } = useI18n();

const expanded = ref(false);
/** null = 还没拉过；[] = 拉过但没有子任务。两者在 UI 上都要区分（转圈 vs 空态）。 */
const kids = ref<TodoTask[] | null>(null);
const loading = ref(false);

const hasChildren = computed(() => (props.task.childTotal ?? 0) > 0);

function childPct(task: TodoTask): number {
  const total = task.childTotal ?? 0;
  if (!total) return 0;
  return Math.round(((task.childDone ?? 0) / total) * 100);
}

/** 勾选框不可用时的原因，空串 = 可用。与父卡片同一套判定，避免两处口径打架。 */
function completeHint(task: TodoTask): string {
  if ((task.pendingChildCount ?? 0) > 0) {
    return t('todo.qt.pendingChildren', { n: task.pendingChildCount });
  }
  if (task.crossDeptReadOnly) return t('todo.crossDeptReadOnly');
  return '';
}

function dueLabel(task: TodoTask): string {
  if (!task.dueAt) return '';
  const target = new Date(task.dueAt);
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const dayTarget = new Date(target.getFullYear(), target.getMonth(), target.getDate());
  const diff = Math.round((dayTarget.getTime() - today.getTime()) / 86400000);
  if (diff < 0) return t('todo.dashboard.overdueDays', { n: Math.abs(diff) });
  if (diff === 0) return t('todo.dashboard.dueToday');
  if (diff === 1) return t('todo.dashboard.dueTomorrow');
  if (diff <= 7) return t('todo.dashboard.dueThisWeek');
  return '';
}

function dueClass(task: TodoTask): string {
  if (!task.dueAt || task.status === 'COMPLETED') return '';
  const d = new Date(task.dueAt).getTime();
  if (d < Date.now()) return 'is-overdue';
  return new Date(task.dueAt).toDateString() === new Date().toDateString() ? 'is-today' : '';
}

async function toggle() {
  if (loading.value) return;
  if (expanded.value) {
    expanded.value = false;
    return;
  }
  expanded.value = true;
  // 拉过一次就不重复请求：子任务可能已被勾掉，重新进 tab 时数据反而更准。
  if (kids.value !== null) return;
  loading.value = true;
  try {
    kids.value = await todoApi.children(props.task.id);
  } catch {
    // 拦截器已弹错；把结果留成 null，下次点开还会重试。
    kids.value = null;
    expanded.value = false;
  } finally {
    loading.value = false;
  }
}

async function completeOne(task: TodoTask, done: boolean) {
  if (!done) return;
  try {
    const nextId = await todoApi.complete(task.id);
    ElMessage.success(nextId ? t('todo.recurrenceSpawned') : t('todo.completed'));
    // 本层的子任务状态可能已变（例如勾的是子任务，它的子级要重新判定置灰）
    kids.value = null;
    emit('mutated');
  } catch {
    /* 拦截器已弹错 */
  }
}
</script>

<template>
  <div class="sub-node" :style="{ '--sub-depth': depth ?? 0 }">
    <div class="sub-row">
      <el-tooltip
        :content="completeHint(task)"
        :disabled="!completeHint(task)"
        placement="right"
      >
        <span class="sub-check-wrap">
          <el-checkbox
            :model-value="task.status === 'COMPLETED'"
            :disabled="task.status === 'COMPLETED' || task.canComplete === false"
            @change="(v) => completeOne(task, v as boolean)"
          />
        </span>
      </el-tooltip>

      <div class="sub-body">
        <div class="sub-main">
          <span class="sub-title" :class="{ 'is-done': task.status === 'COMPLETED' }">
            {{ task.title }}
          </span>
          <span v-if="task.dueAt" class="meta-due" :class="dueClass(task)">{{ dueLabel(task) }}</span>
          <span v-for="tag in task.tagNames || []" :key="tag" class="meta-tag">{{ tag }}</span>
        </div>
        <div v-if="hasChildren" class="sub-progress">
          <button
            type="button"
            class="sub-toggle"
            :aria-expanded="expanded"
            @click="toggle"
          >
            <el-icon class="toggle-caret" :class="{ 'is-open': expanded }">
              <CaretRight />
            </el-icon>
            <span>{{ t('todo.qt.childProgress', { done: task.childDone ?? 0, total: task.childTotal }) }}</span>
          </button>
          <el-progress
            :percentage="childPct(task)"
            :show-text="false"
            :stroke-width="3"
            color="var(--color-primary)"
            class="sub-bar"
          />
          <span class="sub-pct">{{ childPct(task) }}%</span>
        </div>
      </div>
    </div>

    <div v-if="expanded" class="sub-kids">
      <p v-if="loading" class="sub-hint muted">{{ t('todo.qt.loadingChildren') }}</p>
      <template v-else>
        <TodoSubNode
          v-for="kid in kids ?? []"
          :key="kid.id"
          :task="kid"
          :depth="(depth ?? 0) + 1"
          @mutated="emit('mutated')"
        />
        <p v-if="(kids ?? []).length === 0" class="sub-hint muted">{{ t('todo.qt.noChildren') }}</p>
      </template>
    </div>
  </div>
</template>

<style scoped>
.sub-node { padding-left: calc(var(--sub-depth) * 14px); }

.sub-row {
  display: flex; align-items: flex-start; gap: 8px;
  padding: 5px 0;
}
/* disabled 的 el-checkbox 不派发鼠标事件，tooltip 收不到，用 span 兜一层。 */
.sub-check-wrap { display: inline-flex; flex-shrink: 0; align-items: center; padding-top: 1px; }

.sub-body { flex: 1; min-width: 0; }
.sub-main { display: flex; align-items: center; gap: 7px; min-width: 0; }
.sub-title {
  flex: 1; min-width: 0;
  font-size: 12.5px; font-weight: 500; color: var(--color-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.sub-title.is-done { color: var(--color-text-muted); text-decoration: line-through; }

.sub-progress { display: flex; align-items: center; gap: 8px; margin-top: 2px; }
.sub-toggle {
  display: inline-flex; align-items: center; gap: 3px;
  padding: 0; border: 0; background: transparent; cursor: pointer;
  font-size: 11px; font-family: inherit;
  color: var(--color-primary);
}
.sub-toggle:hover { text-decoration: underline; }
.sub-bar { width: 30px; flex-shrink: 0; }
.sub-pct { font-size: 11px; color: var(--color-text-muted); font-variant-numeric: tabular-nums; }

/* 左侧竖条表示层级，和主卡片的左边框呼应；用 border 而非背景色块，保持紧凑。 */
.sub-kids {
  margin-left: 8px;
  padding-left: 8px;
  border-left: 1px solid var(--color-border-soft);
}
.sub-hint { margin: 4px 0 6px; font-size: 11.5px; }

.toggle-caret { font-size: 11px; transition: transform .15s ease; }
.toggle-caret.is-open { transform: rotate(90deg); }
</style>
