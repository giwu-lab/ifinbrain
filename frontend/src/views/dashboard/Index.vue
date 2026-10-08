<template>
  <div class="dashboard-page fade-in-up">
    <!-- 4 个快捷入口卡（点击跳转） -->
    <el-row :gutter="20" class="stat-row">
      <!-- 我的任务（todo:task:view 才有） -->
      <el-col v-if="auth.hasPerm('todo:task:view')" :xs="12" :sm="12" :md="6">
        <div class="stat-card stat-primary clickable fade-in-up" style="animation-delay: 0.05s" @click="goTodo">
          <div class="stat-icon"><el-icon><List /></el-icon></div>
          <div class="stat-body">
            <div class="stat-label">{{ t('dashboard.myTasksCount') }}</div>
            <div class="stat-value">{{ myTasksCount }}</div>
            <div class="stat-foot muted">{{ t('dashboard.myTasksHint') }}</div>
          </div>
        </div>
      </el-col>

      <!-- 待办审批（design:flow:view 才有） -->
      <el-col v-if="auth.hasPerm('design:flow:view')" :xs="12" :sm="12" :md="6">
        <div class="stat-card stat-info clickable fade-in-up" style="animation-delay: 0.10s" @click="goFlowRuntime">
          <div class="stat-icon"><el-icon><Promotion /></el-icon></div>
          <div class="stat-body">
            <div class="stat-label">{{ t('dashboard.flowTodoCount') }}</div>
            <div class="stat-value">{{ flowTodoCount }}</div>
            <div class="stat-foot muted">{{ t('dashboard.flowTodoHint') }}</div>
          </div>
        </div>
      </el-col>

      <!-- 角色数（恒有） -->
      <el-col :xs="12" :sm="12" :md="6">
        <div class="stat-card stat-success clickable fade-in-up" style="animation-delay: 0.15s" @click="goRoles">
          <div class="stat-icon"><el-icon><UserFilled /></el-icon></div>
          <div class="stat-body">
            <div class="stat-label">{{ t('dashboard.rolesCount') }}</div>
            <div class="stat-value">{{ auth.roles.length }}</div>
            <div class="stat-foot">
              <el-tag v-for="r in auth.roles.slice(0, 2)" :key="r" size="small" effect="plain" round>{{ r }}</el-tag>
            </div>
          </div>
        </div>
      </el-col>

      <!-- 菜单数（恒有） -->
      <el-col :xs="12" :sm="12" :md="6">
        <div class="stat-card stat-warning clickable fade-in-up" style="animation-delay: 0.20s" @click="goMenus">
          <div class="stat-icon"><el-icon><Menu /></el-icon></div>
          <div class="stat-body">
            <div class="stat-label">{{ t('dashboard.menusCount') }}</div>
            <div class="stat-value">{{ auth.menus.length }}</div>
            <div class="stat-foot muted">
              <span v-for="m in auth.menus.slice(0, 2)" :key="m.id" class="foot-item">{{ menuName(m) }}</span>
            </div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 我的任务 widget（四象限视图） -->
    <TodoWidget />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRouter } from 'vue-router';
import { List, Menu, Promotion, UserFilled } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { todoApi } from '@/api/todo';
import { flowTaskApi } from '@/api/flowTask';
import TodoWidget from './components/TodoWidget.vue';

const { t } = useI18n();
const router = useRouter();
const auth = useAuthStore();

/** 我的任务数（个人 PENDING todo）—— 取 dashboard 接口的 items.length。
 *  注：dashboard 接口 max limit=50，0~50 范围内准确；>50 时显示 50（点击跳转看全部） */
const myTasksCount = ref(0);
/** 待审批任务数（flow 待办） */
const flowTodoCount = ref(0);

function menuName(m: { name: string; component?: string }) {
  const comp = m.component?.replace(/\//g, '.');
  if (comp) {
    const translated = t(`menu.${comp}`, m.name);
    if (translated !== `menu.${comp}`) return translated;
  }
  return m.name;
}

/* 快捷入口：dashboard 4 卡点击 → 跳转对应页面 */
function goTodo() { router.push('/todo'); }
function goFlowRuntime() { router.push('/design/flow-runtime'); }
function goRoles() { router.push('/system/role'); }
function goMenus() { router.push('/system/menu'); }

onMounted(async () => {
  /* 两个 count 都是轻量 endpoint，并行拉；权限缺失或失败静默（避免登录后立即 403 toast）。
   *  - todoApi.dashboard(1) → 拿到真实 total（admin 走 searchAll，看全员 PENDING 数）
   *  - flowTaskApi.pendingCount() → 真正的 count endpoint
   */
  const tasks = [
    auth.hasPerm('todo:task:view')
      ? todoApi.dashboard(1).then((resp) => { myTasksCount.value = resp.total; }).catch(() => {})
      : Promise.resolve(),
    auth.hasPerm('design:flow:view')
      ? flowTaskApi.pendingCount().then((n) => { flowTodoCount.value = n; }).catch(() => {})
      : Promise.resolve(),
  ];
  await Promise.all(tasks);
});
</script>

<style scoped>
.dashboard-page { padding: 24px; max-width: 1280px; margin: 0 auto; }

/* ===== Stat 卡片 ===== */
.stat-row { margin-bottom: 24px; }
.stat-card {
  background: var(--color-bg-elev);
  border-radius: 14px;
  padding: 20px;
  display: flex;
  gap: 16px;
  align-items: flex-start;
  border: 1px solid var(--color-border-soft);
  transition: transform 0.2s ease, box-shadow 0.2s ease;
  cursor: default;
}
.stat-card.clickable { cursor: pointer; }
.stat-card.clickable:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(37, 99, 235, 0.18);
  border-color: var(--color-primary-soft);
}
.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.06);
}
.stat-icon {
  width: 48px; height: 48px;
  border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  font-size: 22px;
  color: white;
  flex-shrink: 0;
}
.stat-primary .stat-icon { background: linear-gradient(135deg, #3b82f6, #2563eb); }
.stat-success .stat-icon { background: linear-gradient(135deg, #34d399, #10b981); }
.stat-warning .stat-icon { background: linear-gradient(135deg, #fbbf24, #f59e0b); }
.stat-info .stat-icon    { background: linear-gradient(135deg, #818cf8, #6366f1); }
.stat-body { flex: 1; min-width: 0; }
.stat-label { font-size: 13px; color: var(--color-text-secondary); }
.stat-value { font-size: 28px; font-weight: 700; margin-top: 4px; color: var(--color-text); letter-spacing: -0.01em; }
.stat-foot {
  margin-top: 6px;
  font-size: 12px;
  color: var(--color-text-muted);
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.foot-item {
  background: var(--color-bg);
  padding: 2px 8px;
  border-radius: 4px;
}
</style>