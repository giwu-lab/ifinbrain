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

      <!-- 发票数量（invoice:list 才有）：按发票号去重，口径与发票列表「一行 = 一个发票号」一致 -->
      <el-col v-if="auth.hasPerm('invoice:list')" :xs="12" :sm="12" :md="6">
        <div class="stat-card stat-success clickable fade-in-up" style="animation-delay: 0.15s" @click="goInvoices">
          <div class="stat-icon"><el-icon><Tickets /></el-icon></div>
          <div class="stat-body">
            <div class="stat-label">{{ t('dashboard.invoicesCount') }}</div>
            <div class="stat-value">{{ invoiceCount }}</div>
            <div class="stat-foot muted">{{ t('dashboard.invoicesHint') }}</div>
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
import { List, Menu, Promotion, Tickets } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { todoApi } from '@/api/todo';
import { flowTaskApi } from '@/api/flowTask';
import { invoiceApi } from '@/api/invoice';
import TodoWidget from './components/TodoWidget.vue';

const { t } = useI18n();
const router = useRouter();
const auth = useAuthStore();

/** 我的任务数（个人 PENDING todo）—— 取 dashboard 接口的 items.length。
 *  注：dashboard 接口 max limit=50，0~50 范围内准确；>50 时显示 50（点击跳转看全部） */
const myTasksCount = ref(0);
/** 待审批任务数（flow 待办） */
const flowTodoCount = ref(0);
/** 发票数量（按发票号去重）—— 取 /invoices/groups 的 total：后端 GROUP BY 发票号码，
 *  一张发票的 PDF/XML/OFD 三个文件合并成一行，所以 total 是「发票张数」而不是文件数。
 *  size=1 只为拿到 total，不拉明细（offset=0、1 行，开销与 limit 无关）。 */
const invoiceCount = ref(0);

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
function goInvoices() { router.push('/invoice'); }
function goMenus() { router.push('/system/menu'); }

onMounted(async () => {
  /* 三个 count 都是轻量 endpoint，并行拉；权限缺失或失败静默（避免登录后立即 403 toast）。
   *  - todoApi.dashboard(1) → 拿到真实 total（admin 走 searchAll，看全员 PENDING 数）
   *  - flowTaskApi.pendingCount() → 真正的 count endpoint
   *  - invoiceApi.listGrouped({page:1,size:1}) → 后端 GROUP BY 发票号分组的 total = 发票张数
   */
  const tasks = [
    auth.hasPerm('todo:task:view')
      ? todoApi.dashboard(1).then((resp) => { myTasksCount.value = resp.total; }).catch(() => {})
      : Promise.resolve(),
    auth.hasPerm('design:flow:view')
      ? flowTaskApi.pendingCount().then((n) => { flowTodoCount.value = n; }).catch(() => {})
      : Promise.resolve(),
    auth.hasPerm('invoice:list')
      ? invoiceApi.listGrouped({ page: 1, size: 1 }).then((r) => { invoiceCount.value = r.total; }).catch(() => {})
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