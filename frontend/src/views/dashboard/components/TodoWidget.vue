<template>
  <div v-if="auth.hasPerm('todo:task:view')" class="todo-quadrant">
    <!-- ===== Tab 条 ===== -->
    <div class="qt-tabs" role="tablist">
      <button
        v-for="tab in TABS"
        :key="tab.key"
        class="qt-tab"
        :class="{ 'is-active': activeTab === tab.key, 'is-disabled': tab.disabled }"
        :disabled="tab.disabled"
        role="tab"
        :aria-selected="activeTab === tab.key"
        @click="switchTab(tab.key)"
      >
        <el-icon><component :is="tab.icon" /></el-icon>
        <span>{{ t(tab.labelKey) }}</span>
        <span v-if="tab.badge()" class="qt-badge">{{ tab.badge() }}</span>
      </button>
    </div>

    <div v-loading="loading" class="qt-body">
      <!-- ===== 统计条 =====
           洞察 tab 不显示：那里的 6 张指标卡已经覆盖了完成率 / 积压 / 预估，
           再挂一条「进行中 / 今日到期 / 已逾期 / 完成率」既重复又把页面撑长。
           统计条回答「此刻有多少」，指标卡回答「这段时间怎么样」，洞察页自成一体。 -->
      <div v-if="activeTab !== 'insight'" class="qt-stats">
        <div class="qt-stat">
          <div class="qt-stat-label">{{ t('todo.stats.pending') }}</div>
          <div class="qt-stat-value">{{ stats.pending }}</div>
        </div>
        <div class="qt-stat" :class="{ 'is-today': stats.dueToday > 0 }">
          <div class="qt-stat-label">{{ t('todo.stats.dueToday') }}</div>
          <div class="qt-stat-value">{{ stats.dueToday }}</div>
        </div>
        <div class="qt-stat" :class="{ 'is-overdue': stats.overdue > 0 }">
          <div class="qt-stat-label">{{ t('todo.stats.overdue') }}</div>
          <div class="qt-stat-value">{{ stats.overdue }}</div>
        </div>
        <div class="qt-stat is-rate">
          <div class="qt-stat-head">
            <span class="qt-stat-label">{{ t('todo.stats.completionRate') }}</span>
            <span class="qt-rate-num">{{ ratePercent }}%</span>
          </div>
          <el-progress
            :percentage="ratePercent"
            :show-text="false"
            :stroke-width="6"
            color="var(--color-primary)"
          />
          <div class="qt-stat-foot muted">{{ t('todo.stats.rateFoot', { done: stats.completed, total: stats.total }) }}</div>
        </div>
      </div>

      <!-- ===== 工具栏（洞察 tab 无任务列表可筛，隐藏）===== -->
      <div v-if="activeTab !== 'insight'" class="qt-toolbar">
        <el-input
          v-model="filters.keyword"
          :placeholder="t('todo.qt.searchPlaceholder')"
          clearable
          :prefix-icon="Search"
          style="flex: 1; min-width: 180px;"
        />
        <el-select v-model="filters.tag" :placeholder="t('todo.qt.allTags')" clearable style="width: 140px;">
          <el-option :label="t('todo.qt.allTags')" value="" />
          <el-option v-for="tag in allTags" :key="tag" :label="tag" :value="tag" />
        </el-select>
        <el-select v-model="filters.sort" style="width: 160px;">
          <el-option :label="t('todo.qt.sortSmart')" value="smart" />
          <el-option :label="t('todo.qt.sortDue')" value="due" />
          <el-option :label="t('todo.qt.sortPriority')" value="priority" />
        </el-select>
        <el-button link :icon="Sort" :title="t('todo.qt.sort')" />
      </div>

      <!-- ===== 四象限 tab：2×2 网格 ===== -->
      <div v-if="activeTab === 'quadrant'" class="qt-grid">
        <section
          v-for="bucket in visibleQuadrants"
          :key="bucket.key"
          class="qt-cell"
          :class="bucket.key.toLowerCase()"
        >
          <header class="qt-cell-head">
            <el-icon class="qt-cell-icon"><component :is="QUAD[bucket.key].icon" /></el-icon>
            <div class="qt-cell-title">
              <span class="qt-cell-name">{{ t(QUAD[bucket.key].nameKey) }}</span>
              <span class="qt-cell-sub">{{ t(QUAD[bucket.key].subKey) }}</span>
            </div>
            <span class="qt-cell-count">{{ bucket.count }}</span>
          </header>
          <div class="qt-cell-spend">{{ t(QUAD[bucket.key].spendKey) }}</div>
          <p class="qt-cell-tip">{{ t(QUAD[bucket.key].tipKey) }}</p>

          <div class="qt-cell-tasks">
            <!-- 两行紧凑布局，四项信息全部常驻可见：
                 行1 = 标题 + 右侧 截止/时长/标签
                 行2 = 描述 + 右侧 子任务进度
                 行2 在「既无描述又无子任务」时整行省略，卡片退化成单行。 -->
            <article v-for="task in bucket.tasks" :key="task.id" class="qt-task">
              <el-tooltip
                :content="completeHint(task)"
                :disabled="!completeHint(task)"
                placement="right"
              >
                <span class="qt-check-wrap">
                  <el-checkbox
                    :model-value="task.status === 'COMPLETED'"
                    :disabled="task.status === 'COMPLETED' || task.canComplete === false"
                    @change="(v) => completeOne(task, v as boolean)"
                  />
                </span>
              </el-tooltip>
              <div class="qt-task-body">
                <div class="qt-task-main">
                  <span class="qt-task-title">{{ task.title }}</span>
                  <span class="meta-due" :class="dueClass(task)">{{ dueLabel(task) }}</span>
                  <span v-if="task.estimateMinutes" class="meta-est">
                    <el-icon><Clock /></el-icon>{{ fmtMinutes(task.estimateMinutes) }}
                  </span>
                  <span v-for="tag in task.tagNames || []" :key="tag" class="meta-tag">{{ tag }}</span>
                </div>
                <div v-if="task.description || (task.childTotal ?? 0) > 0" class="qt-task-sub">
                  <span class="qt-task-desc">{{ task.description }}</span>
                  <div v-if="(task.childTotal ?? 0) > 0" class="qt-task-progress">
                    <!-- 子任务进度区同时充当「展开/收起」触发器：进度数字本身就是
                         用户最想点的位置，不必另加一个图标按钮挤占横向空间。 -->
                    <button
                      type="button"
                      class="progress-toggle"
                      :aria-expanded="isExpanded(task.id)"
                      @click="toggleChildren(task)"
                    >
                      <el-icon class="toggle-caret" :class="{ 'is-open': isExpanded(task.id) }">
                        <CaretRight />
                      </el-icon>
                      <span class="progress-text">
                        {{ t('todo.qt.childProgress', { done: task.childDone ?? 0, total: task.childTotal }) }}
                      </span>
                    </button>
                    <el-progress
                      :percentage="childPct(task)"
                      :show-text="false"
                      :stroke-width="4"
                      color="var(--color-primary)"
                      class="progress-bar"
                    />
                    <span class="progress-pct">{{ childPct(task) }}%</span>
                  </div>
                </div>
              </div>

              <!-- 子任务：默认隐藏，点进度区才懒加载。递归支持任意层级。 -->
              <div v-if="isExpanded(task.id)" class="qt-subtasks">
                <p v-if="loadingChildren.has(task.id)" class="qt-sub-loading muted">
                  {{ t('todo.qt.loadingChildren') }}
                </p>
                <TodoSubNode
                  v-for="kid in (childrenOf(task.id) ?? [])"
                  :key="kid.id"
                  :task="kid"
                  :depth="0"
                  @mutated="reload"
                />
                <p v-if="!loadingChildren.has(task.id) && (childrenOf(task.id) ?? []).length === 0"
                   class="qt-sub-empty muted">
                  {{ t('todo.qt.noChildren') }}
                </p>
              </div>
            </article>

            <p v-if="bucket.tasks.length === 0" class="qt-cell-empty muted">
              {{ t('todo.qt.empty') }}
            </p>
          </div>

          <div class="qt-quick-add">
            <el-input
              v-model="quickDraft[bucket.key]"
              :placeholder="t('todo.qt.quickAdd', { name: t(QUAD[bucket.key].nameKey) })"
              @keyup.enter="quickCreate(bucket.key)"
            />
            <el-button
              :icon="Plus"
              :loading="creating === bucket.key"
              @click="quickCreate(bucket.key)"
            />
          </div>
        </section>
      </div>

      <!-- ===== 今天 tab ===== -->
      <div v-else-if="activeTab === 'today'" class="qt-list">
        <p v-if="todayItems.length === 0" class="qt-empty muted">
          {{ t('todo.qt.todayEmpty') }}
        </p>
        <article v-for="task in todayItems" :key="task.id" class="qt-row">
          <el-tooltip
            :content="completeHint(task)"
            :disabled="!completeHint(task)"
            placement="right"
          >
            <span class="qt-check-wrap">
              <el-checkbox
                :model-value="task.status === 'COMPLETED'"
                :disabled="task.status === 'COMPLETED' || task.canComplete === false"
                @change="(v) => completeOne(task, v as boolean)"
              />
            </span>
          </el-tooltip>
          <div class="qt-row-body">
            <div class="qt-row-title">{{ task.title }}</div>
            <div class="qt-row-meta">
              <span class="quad-chip" :class="task.quadrant?.toLowerCase()">
                {{ task.quadrant ? t(`todo.quad.${task.quadrant}.name`) : '' }}
              </span>
              <span class="meta-due" :class="dueClass(task)">{{ dueLabel(task) }}</span>
              <span v-if="task.estimateMinutes" class="meta-est">
                <el-icon><Clock /></el-icon>{{ fmtMinutes(task.estimateMinutes) }}
              </span>
              <span v-for="tag in task.tagNames || []" :key="tag" class="meta-tag">{{ tag }}</span>
            </div>
          </div>
          <span v-if="task.crossDeptReadOnly" class="qt-locked" :title="t('todo.crossDeptReadOnly')">
            <el-icon><View /></el-icon>
          </span>
        </article>
      </div>

      <!-- ===== 归档 tab ===== -->
      <div v-else-if="activeTab === 'archive'" class="qt-list">
        <p v-if="archived.length === 0" class="qt-empty muted">{{ t('todo.qt.archiveEmpty') }}</p>
        <article v-for="task in archived" :key="task.id" class="qt-row is-done">
          <el-icon class="qt-row-check"><CircleCheck /></el-icon>
          <div class="qt-row-body">
            <div class="qt-row-title">{{ task.title }}</div>
            <div class="qt-row-meta">
              <span class="meta-time muted nowrap">{{
                t('todo.dashboard.completedAt', { time: fmtStamp(task.completedAt || task.updatedAt) })
              }}</span>
              <span v-for="tag in task.tagNames || []" :key="tag" class="meta-tag">{{ tag }}</span>
            </div>
          </div>
        </article>
      </div>

      <!-- ===== 洞察 tab ===== -->
      <TodoInsight v-else-if="activeTab === 'insight'" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, type Component } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import {
  Search, Plus, Sort, Clock, Grid, Sunny, TrendCharts, Box,
  WarningFilled, Calendar, Share, CircleClose, CircleCheck, View, CaretRight,
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import TodoSubNode from './TodoSubNode.vue';
import TodoInsight from './TodoInsight.vue';
import { formatDateTime } from '@/utils/time';
import {
  todoApi,
  type TodoTask, type TodoStats, type TodoQuadrantBucket,
} from '@/api/todo';

const { t } = useI18n();
const auth = useAuthStore();

type TabKey = 'quadrant' | 'today' | 'insight' | 'archive';

interface QuadMeta {
  icon: Component;
  nameKey: string;
  subKey: string;
  spendKey: string;
  tipKey: string;
}

/** 四象限的静态元数据。判定逻辑在后端（TodoQuadrant），这里只负责呈现。 */
const QUAD: Record<string, QuadMeta> = {
  Q1: { icon: WarningFilled, nameKey: 'todo.quad.Q1.name', subKey: 'todo.quad.Q1.sub', spendKey: 'todo.quad.Q1.spend', tipKey: 'todo.quad.Q1.tip' },
  Q2: { icon: Calendar,      nameKey: 'todo.quad.Q2.name', subKey: 'todo.quad.Q2.sub', spendKey: 'todo.quad.Q2.spend', tipKey: 'todo.quad.Q2.tip' },
  Q3: { icon: Share,         nameKey: 'todo.quad.Q3.name', subKey: 'todo.quad.Q3.sub', spendKey: 'todo.quad.Q3.spend', tipKey: 'todo.quad.Q3.tip' },
  Q4: { icon: CircleClose,   nameKey: 'todo.quad.Q4.name', subKey: 'todo.quad.Q4.sub', spendKey: 'todo.quad.Q4.spend', tipKey: 'todo.quad.Q4.tip' },
};

/**
 * 快速添加的预设规则：落进哪个象限，就得用对应的 priority / dueAt，
 * 否则后端重新推导时任务会掉到别的格子去，用户会以为"添加到 A 却出现在 B"。
 */
const QUICK_PRESET: Record<string, { priority: TodoTask['priority']; dueOffsetDays: number | null }> = {
  Q1: { priority: 'HIGH', dueOffsetDays: 0 },
  Q2: { priority: 'HIGH', dueOffsetDays: 7 },
  Q3: { priority: 'LOW',  dueOffsetDays: 0 },
  Q4: { priority: 'NONE', dueOffsetDays: null },
};

const TABS: { key: TabKey; icon: Component; labelKey: string; disabled?: boolean; badge: () => number | null }[] = [
  { key: 'quadrant', icon: Grid,        labelKey: 'todo.tabs.qtQuadrant', badge: () => stats.value.pending || null },
  { key: 'today',    icon: Sunny,       labelKey: 'todo.tabs.qtToday',    badge: () => (stats.value.overdue + stats.value.dueToday) || null },
  { key: 'insight',  icon: TrendCharts, labelKey: 'todo.tabs.qtInsight',  badge: () => null },
  { key: 'archive',  icon: Box,         labelKey: 'todo.tabs.qtArchive',  badge: () => stats.value.completed || null },
];

const activeTab = ref<TabKey>('quadrant');
const loading = ref(false);
const creating = ref<string | null>(null);

const stats = ref<TodoStats>({ pending: 0, dueToday: 0, overdue: 0, completed: 0, total: 0, completionRate: 0 });
const quadrants = ref<TodoQuadrantBucket[]>([]);
const todayItems = ref<TodoTask[]>([]);
const archived = ref<TodoTask[]>([]);

const filters = reactive({ keyword: '', tag: '', sort: 'smart' });
const quickDraft = reactive<Record<string, string>>({ Q1: '', Q2: '', Q3: '', Q4: '' });

/**
 * 顶层父任务的展开态。子任务自己的展开态在 TodoSubNode 内部管理，
 * 这里只管象限卡片这一层。
 * 用 Set 而不是普通对象：id 是数字，Set 的 has/in 判断比对象键更快也更安全。
 */
const expandedIds = ref<Set<number>>(new Set());
const childCache = ref<Map<number, TodoTask[]>>(new Map());
const loadingChildren = ref<Set<number>>(new Set());

function isExpanded(id: number): boolean {
  return expandedIds.value.has(id);
}
function childrenOf(id: number): TodoTask[] | undefined {
  return childCache.value.get(id);
}

async function toggleChildren(task: TodoTask) {
  if (loadingChildren.value.has(task.id)) return;
  if (expandedIds.value.has(task.id)) {
    // 收起：保留 childCache，下次展开不必重新请求
    const next = new Set(expandedIds.value);
    next.delete(task.id);
    expandedIds.value = next;
    return;
  }
  const next = new Set(expandedIds.value);
  next.add(task.id);
  expandedIds.value = next;

  if (childCache.value.has(task.id)) return; // 拉过就不重复请求
  loadingChildren.value = new Set(loadingChildren.value).add(task.id);
  try {
    const kids = await todoApi.children(task.id);
    const cache = new Map(childCache.value);
    cache.set(task.id, kids);
    childCache.value = cache;
  } catch {
    // 拦截器已弹错；顺手收起，避免留下一个永远空白的展开块
    const back = new Set(expandedIds.value);
    back.delete(task.id);
    expandedIds.value = back;
  } finally {
    const loading = new Set(loadingChildren.value);
    loading.delete(task.id);
    loadingChildren.value = loading;
  }
}

/** 象限数据整体重载后，展开态和缓存都指向旧 id 的数据，必须一起清掉。 */
function clearChildrenState() {
  expandedIds.value = new Set();
  childCache.value = new Map();
  loadingChildren.value = new Set();
}

const ratePercent = computed(() => Math.round((stats.value.completionRate || 0) * 100));

const allTags = computed(() => {
  const set = new Set<string>();
  for (const b of quadrants.value) for (const t of b.tasks) for (const tag of t.tagNames || []) set.add(tag);
  for (const t of todayItems.value) for (const tag of t.tagNames || []) set.add(tag);
  return [...set].sort();
});

const PRIO_RANK: Record<string, number> = { HIGH: 0, MEDIUM: 1, LOW: 2, NONE: 3 };

/** 前端筛选 + 排序。数据已全量取回（上限 200/500），这里做纯内存处理。 */
function applyFilters(list: TodoTask[]): TodoTask[] {
  const kw = filters.keyword.trim().toLowerCase();
  let out = list.filter((t) => {
    if (filters.tag && !(t.tagNames || []).includes(filters.tag)) return false;
    if (kw) {
      const inTitle = (t.title || '').toLowerCase().includes(kw);
      const inDesc = (t.description || '').toLowerCase().includes(kw);
      if (!inTitle && !inDesc) return false;
    }
    return true;
  });
  if (filters.sort === 'priority') {
    out = [...out].sort((a, b) => (PRIO_RANK[a.priority] ?? 9) - (PRIO_RANK[b.priority] ?? 9));
  } else if (filters.sort === 'due') {
    const ts = (t: TodoTask) => (t.dueAt ? new Date(t.dueAt).getTime() : Number.POSITIVE_INFINITY);
    out = [...out].sort((a, b) => ts(a) - ts(b));
  }
  return out;
}

const visibleQuadrants = computed<TodoQuadrantBucket[]>(() =>
  quadrants.value.map((b) => ({ ...b, tasks: applyFilters(b.tasks) })),
);

async function loadQuadrant() {
  const r = await todoApi.quadrant(200);
  quadrants.value = r.quadrants;
  stats.value = r.stats;
}

async function loadToday() {
  const r = await todoApi.today(50);
  todayItems.value = r.items;
  stats.value = r.stats;
}

async function loadArchive() {
  const r = await todoApi.list({ page: 1, size: 20, status: 'COMPLETED' });
  archived.value = r.items;
}

async function reload() {
  loading.value = true;
  // 勾掉一个子任务会改变父任务的 childDone/pendingChildCount/canComplete，
  // 已展开的那几个父任务要重新拉子任务，否则进度条和置灰态会停在旧值。
  const reopen = [...expandedIds.value];
  try {
    if (activeTab.value === 'quadrant') await loadQuadrant();
    else if (activeTab.value === 'today') await loadToday();
    else if (activeTab.value === 'archive') await loadArchive();
  } catch {
    /* 拦截器已弹错 */
  } finally {
    loading.value = false;
  }
  if (reopen.length) await refreshChildrenOf(reopen);
}

/**
 * 归档 tab 的时间戳：后端给的是 2026-10-07T11:22:33 这种 ISO 串，
 * 直接插值会把中间的 "T" 也显示出来。统一走 formatDateTime 规整成
 * 「2026-10-07 11:22:33」，空值显示占位符而不是空白。
 */
function fmtStamp(s?: string | null): string {
  return formatDateTime(s);
}

/** 重新拉取指定父任务的子任务，保持展开态；失败时静默，展开块留旧数据也不算错。 */
async function refreshChildrenOf(ids: number[]) {
  try {
    const results = await Promise.all(ids.map((id) => todoApi.children(id)));
    const cache = new Map(childCache.value);
    ids.forEach((id, i) => cache.set(id, results[i]));
    childCache.value = cache;
  } catch {
    /* 拦截器已弹错 */
  }
}

function switchTab(key: TabKey) {
  const tab = TABS.find((x) => x.key === key);
  if (!tab || tab.disabled) return;
  if (activeTab.value === key) return;
  activeTab.value = key;
  // 展开态和缓存按象限 id 索引，换 tab 后那批任务已不在当前列表里
  clearChildrenState();
  reload();
}

async function completeOne(task: TodoTask, done: boolean) {
  if (!done) return;
  try {
    const nextId = await todoApi.complete(task.id);
    ElMessage.success(nextId ? t('todo.recurrenceSpawned') : t('todo.completed'));
    reload();
  } catch {
    /* 拦截器已弹错 */
  }
}

/**
 * 勾选框不可用时的原因，空串 = 可用（tooltip 自动禁用）。
 * 两种原因要分开说：等子任务是任务自身还没做完，跨部门只读是权限不够。
 */
function completeHint(task: TodoTask): string {
  if ((task.pendingChildCount ?? 0) > 0) {
    return t('todo.qt.pendingChildren', { n: task.pendingChildCount });
  }
  if (task.crossDeptReadOnly) return t('todo.crossDeptReadOnly');
  return '';
}

/** 快速添加到指定象限：按 QUICK_PRESET 预设 priority / dueAt 后创建。 */
async function quickCreate(key: string) {
  const title = (quickDraft[key] || '').trim();
  if (!title) return;
  const preset = QUICK_PRESET[key];
  creating.value = key;
  try {
    let dueAt: string | undefined;
    if (preset.dueOffsetDays !== null) {
      const d = new Date();
      d.setDate(d.getDate() + preset.dueOffsetDays);
      d.setHours(18, 0, 0, 0);
      const pad = (n: number) => String(n).padStart(2, '0');
      // 带 T 的 ISO 格式 —— 后端 @JsonFormat 只认这个分隔符
      dueAt = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T18:00:00`;
    }
    await todoApi.create({ title, priority: preset.priority, dueAt: dueAt ?? null });
    ElMessage.success(t('common.created'));
    quickDraft[key] = '';
    reload();
  } finally {
    creating.value = null;
  }
}

/** 45 → "45 分钟"；90 → "1 小时 30 分钟"。 */
function fmtMinutes(m: number): string {
  if (!m || m < 60) return t('todo.qt.minutes', { n: m || 0 });
  const h = Math.floor(m / 60);
  const r = m % 60;
  return r ? t('todo.qt.hoursMinutes', { h, m: r }) : t('todo.qt.hours', { h });
}

function childPct(task: TodoTask): number {
  const total = task.childTotal ?? 0;
  if (!total) return 0;
  return Math.round(((task.childDone ?? 0) / total) * 100);
}

function dueClass(task: TodoTask): string {
  if (!task.dueAt || task.status === 'COMPLETED') return '';
  const d = new Date(task.dueAt).getTime();
  if (d < Date.now()) return 'is-overdue';
  return new Date(task.dueAt).toDateString() === new Date().toDateString() ? 'is-today' : '';
}

function dueLabel(task: TodoTask): string {
  if (!task.dueAt) return t('todo.dashboard.dueNoDate');
  const target = new Date(task.dueAt);
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const dayTarget = new Date(target.getFullYear(), target.getMonth(), target.getDate());
  const diff = Math.round((dayTarget.getTime() - today.getTime()) / 86400000);
  if (diff < 0) return t('todo.dashboard.overdueDays', { n: Math.abs(diff) });
  if (diff === 0) return t('todo.dashboard.dueToday');
  if (diff === 1) return t('todo.dashboard.dueTomorrow');
  if (diff <= 7) return t('todo.dashboard.dueThisWeek');
  const pad = (n: number) => String(n).padStart(2, '0');
  return t('todo.dashboard.dueFuture', {
    date: `${target.getFullYear()}-${pad(target.getMonth() + 1)}-${pad(target.getDate())}`,
  });
}

onMounted(reload);
</script>

<style scoped>
/* ================= Tab 条 ================= */
.todo-quadrant { margin-bottom: 24px; }
.qt-tabs {
  display: flex; gap: 4px; padding: 4px;
  background: var(--color-bg); border: 1px solid var(--color-border-soft);
  border-radius: 10px; margin-bottom: 16px;
}
.qt-tab {
  flex: 1; display: flex; align-items: center; justify-content: center; gap: 7px;
  padding: 9px 12px; border: 0; border-radius: 8px; cursor: pointer;
  background: transparent; color: var(--color-text-secondary);
  font-size: 13px; font-weight: 500; font-family: inherit;
  transition: background .15s ease, color .15s ease;
}
.qt-tab:hover:not(:disabled):not(.is-active) { background: var(--color-bg-popover); color: var(--color-text); }
.qt-tab.is-active {
  background: var(--color-bg-elev); color: var(--color-text); font-weight: 600;
  box-shadow: 0 1px 3px rgba(15, 23, 42, .08);
}
.qt-tab.is-disabled { opacity: .4; cursor: not-allowed; }
.qt-badge {
  min-width: 18px; padding: 0 5px; border-radius: 9px;
  background: var(--color-primary-soft); color: var(--color-primary);
  font-size: 11px; font-weight: 600; text-align: center;
}

/* ================= 统计条 ================= */
.qt-stats {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px;
}
.qt-stat {
  background: var(--color-bg-elev); border: 1px solid var(--color-border-soft);
  border-radius: 10px; padding: 12px 14px;
}
.qt-stat-label { font-size: 12px; color: var(--color-text-muted); margin-bottom: 4px; }
.qt-stat-value {
  font-size: 24px; font-weight: 700; color: var(--color-text);
  font-variant-numeric: tabular-nums; line-height: 1.1;
}
.qt-stat.is-today .qt-stat-value { color: var(--color-warning); }
.qt-stat.is-overdue .qt-stat-value { color: var(--color-danger); }
.qt-stat-head { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 6px; }
.qt-rate-num {
  font-size: 18px; font-weight: 700; color: var(--color-text);
  font-variant-numeric: tabular-nums;
}
.qt-stat-foot { font-size: 11px; margin-top: 5px; }

/* ================= 工具栏 ================= */
.qt-toolbar {
  display: flex; gap: 8px; align-items: center;
  padding: 10px 12px; margin-bottom: 16px;
  background: var(--color-bg); border: 1px solid var(--color-border-soft); border-radius: 10px;
}

/* ================= 2×2 象限网格 ================= */
.qt-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
@media (max-width: 1100px) { .qt-grid { grid-template-columns: 1fr; } }

.qt-cell {
  background: var(--color-bg-elev); border: 1px solid var(--color-border-soft);
  border-radius: 12px; padding: 14px 16px 16px; display: flex; flex-direction: column;
  border-left: 3px solid var(--q-color, var(--color-border));
}
.qt-cell.q1 { --q-color: var(--color-danger);  --q-soft: var(--color-danger-soft); }
.qt-cell.q2 { --q-color: var(--color-info);    --q-soft: var(--color-info-soft); }
.qt-cell.q3 { --q-color: var(--color-warning); --q-soft: var(--color-warning-soft); }
.qt-cell.q4 { --q-color: var(--color-text-muted); --q-soft: var(--color-bg); }

.qt-cell-head { display: flex; align-items: center; gap: 10px; }
.qt-cell-icon {
  font-size: 20px; color: var(--q-color);
  background: var(--q-soft); border-radius: 8px; padding: 6px; flex-shrink: 0;
}
.qt-cell-title { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.qt-cell-name { font-size: 15px; font-weight: 600; color: var(--color-text); }
.qt-cell-sub { font-size: 11px; color: var(--color-text-muted); margin-top: 1px; }
.qt-cell-count {
  font-size: 13px; font-weight: 700; color: var(--q-color);
  background: var(--q-soft); border-radius: 6px; padding: 1px 7px;
  font-variant-numeric: tabular-nums; flex-shrink: 0;
}
.qt-cell-spend { font-size: 11px; color: var(--color-text-muted); margin-top: 6px; text-align: right; }
.qt-cell-tip { font-size: 12px; color: var(--color-text-secondary); margin: 4px 0 10px; }

/* 象限内任务卡：两行，四项信息全部常驻 */
.qt-cell-tasks { flex: 1; display: flex; flex-direction: column; gap: 6px; min-height: 60px; }
/* el-tooltip 不加 DOM 包裹，但内层 span 用来兜住 flex 收缩 + 承载 hover 事件
   （disabled 的 el-checkbox 自身不派发鼠标事件，直接包 tooltip 会没有提示）。 */
.qt-check-wrap { display: inline-flex; flex-shrink: 0; align-items: center; }
.qt-task {
  display: flex; gap: 10px; padding: 8px 12px; flex-wrap: wrap;
  background: var(--color-bg); border: 1px solid var(--color-border-soft);
  border-radius: 8px;
}
.qt-task:hover { border-color: var(--color-border-strong); }
.qt-task-body { flex: 1; min-width: 0; }
/* 子任务区是 .qt-task（横向 flex）的第三个子元素，用 flex-basis:100% 强制换行到卡片下方，
   否则它会被挤到标题右侧同一行里。 */
.qt-subtasks { flex: 0 0 100%; }

/* 行1：标题 + 右侧 截止/时长/标签 */
.qt-task-main { display: flex; align-items: center; gap: 8px; min-width: 0; }
.qt-task-title {
  flex: 1; min-width: 0;
  font-size: 13px; font-weight: 600; color: var(--color-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.meta-due { flex-shrink: 0; font-size: 11px; font-variant-numeric: tabular-nums; }
.meta-due.is-overdue { color: var(--color-danger); font-weight: 600; }
.meta-due.is-today { color: var(--color-warning); font-weight: 600; }
.meta-est {
  display: inline-flex; align-items: center; gap: 3px; flex-shrink: 0;
  font-size: 11px; color: var(--color-text-muted);
}
.meta-tag {
  flex-shrink: 0;
  background: var(--color-primary-soft); color: var(--color-primary);
  border-radius: 4px; padding: 0 5px; font-size: 11px;
  max-width: 82px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

/* 行2：描述 + 右侧 子任务进度 */
.qt-task-sub {
  display: flex; align-items: center; gap: 10px;
  margin-top: 3px; min-width: 0;
}
.qt-task-desc {
  flex: 1; min-width: 0; font-size: 12px; color: var(--color-text-muted);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.qt-task-progress { flex-shrink: 0; display: flex; align-items: center; gap: 6px; }
.progress-text, .progress-pct {
  font-size: 11px; color: var(--color-text-muted);
  font-variant-numeric: tabular-nums; white-space: nowrap;
}
.progress-bar { width: 34px; }

/* 「子任务 n/m」既是进度读数也是展开开关 —— 不另加图标按钮，避免横向空间被吃光。
   用 button 而非 span：键盘可达、语义正确。 */
.progress-toggle {
  display: inline-flex; align-items: center; gap: 3px;
  padding: 0; border: 0; background: transparent; cursor: pointer;
  font-size: 11px; font-family: inherit; color: var(--color-text-muted);
  font-variant-numeric: tabular-nums; white-space: nowrap;
}
.progress-toggle:hover { color: var(--color-primary); }
.progress-toggle:hover .progress-text { color: var(--color-primary); }
.toggle-caret { font-size: 11px; transition: transform .15s ease; }
.toggle-caret.is-open { transform: rotate(90deg); }

/* 子任务展开区：左侧竖条表示层级归属，用 border 而非背景块，保持卡片紧凑。 */
.qt-subtasks {
  margin: 6px 0 2px 22px;
  padding-left: 10px;
  border-left: 1px solid var(--color-border-soft);
}
.qt-sub-loading, .qt-sub-empty { margin: 2px 0 4px; font-size: 11.5px; }

.qt-cell-empty { font-size: 12px; text-align: center; padding: 20px 0; }

/* 时间戳强制单行，避免在 flex-wrap 的 meta 行里被折成两行。 */
.nowrap { white-space: nowrap; }

/* 快速添加 */
.qt-quick-add { display: flex; gap: 6px; margin-top: 12px; }

/* ================= 今天 / 归档 列表 ================= */
.qt-list {
  background: var(--color-bg-elev); border: 1px solid var(--color-border-soft);
  border-radius: 12px; padding: 6px 0;
}
.qt-row {
  display: flex; align-items: flex-start; gap: 12px;
  padding: 11px 16px; border-bottom: 1px solid var(--color-border-soft);
}
.qt-row:last-child { border-bottom: 0; }
.qt-row:hover { background: var(--color-bg); }
.qt-row-body { flex: 1; min-width: 0; }
.qt-row-title { font-size: 13px; font-weight: 500; color: var(--color-text); }
.qt-row.is-done .qt-row-title { color: var(--color-text-muted); text-decoration: line-through; }
.qt-row-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 4px; font-size: 11px; color: var(--color-text-muted); }
.qt-row-check { color: var(--color-success); font-size: 16px; margin-top: 1px; }
.qt-locked { color: var(--color-text-muted); font-size: 15px; flex-shrink: 0; }
.quad-chip { border-radius: 4px; padding: 0 6px; font-size: 11px; }
.quad-chip.q1 { background: var(--color-danger-soft); color: var(--color-danger); }
.quad-chip.q2 { background: var(--color-info-soft); color: var(--color-info); }
.quad-chip.q3 { background: var(--color-warning-soft); color: var(--color-warning); }
.quad-chip.q4 { background: var(--color-bg); color: var(--color-text-muted); }

.qt-empty { text-align: center; padding: 40px 16px; font-size: 13px; }
.muted { color: var(--color-text-muted); }
</style>
