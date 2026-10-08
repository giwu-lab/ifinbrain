<template>
  <div class="page-container fade-in-up bi-page">
    <div class="table-wrap bi-table-wrap">
      <div class="table-actions">
        <el-button
          v-if="auth.hasPerm('bi:dashboard:view')"
          :icon="Files"
          @click="openWall"
        >{{ t('bi.dash.wall') }}</el-button>
        <el-button
          v-if="auth.hasPerm('bi:dashboard:create')"
          type="primary"
          :icon="Plus"
          @click="openCreate"
        >{{ t('bi.dash.create') }}</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" border stripe size="small">
        <el-table-column :label="t('bi.dash.name')" min-width="200">
          <template #default="{ row }">
            <div class="name-cell">
              <span class="name-text">{{ row.name }}</span>
              <code class="name-code">{{ row.code }}</code>
            </div>
          </template>
        </el-table-column>

        <el-table-column :label="t('bi.dash.description')" min-width="180">
          <template #default="{ row }">
            <span class="muted small">{{ row.description || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('bi.dash.theme')" width="120">
          <template #default="{ row }">
            <span class="muted small">{{ t(`bi.theme.${row.layout?.theme?.preset || 'default'}`) }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('bi.dash.widgets')" width="90" align="right">
          <template #default="{ row }">
            <span class="tabular">{{ row.layout?.widgets?.length ?? 0 }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('bi.dash.canvas')" width="120">
          <template #default="{ row }">
            <span class="tabular small">
              {{ row.layout?.canvas?.width ?? '-' }} × {{ row.layout?.canvas?.height ?? '-' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column :label="t('bi.dash.status')" width="90" align="center">
          <template #default="{ row }">
            <span class="status-mark" :class="row.status === 1 ? 'on' : 'off'">
              <span class="dot" />
              {{ t(row.status === 1 ? 'bi.dash.published' : 'bi.dash.draft') }}
            </span>
          </template>
        </el-table-column>

        <el-table-column :label="t('bi.dash.updatedAt')" width="180">
          <template #default="{ row }">
            <span class="mono-time">{{ formatTime(row.updatedAt) }}</span>
          </template>
        </el-table-column>

        <el-table-column
          :label="t('common.actions')"
          width="200"
          align="center"
          fixed="right"
        >
          <template #default="{ row }">
            <!-- 高频操作：图标直显（AGENTS §8.2） -->
            <el-tooltip v-if="auth.hasPerm('bi:dashboard:update')" :content="t('bi.dash.edit')" placement="top">
              <el-button size="small" link :icon="Edit" @click="openEdit(row as unknown as BiDashboardDto)" />
            </el-tooltip>

            <el-tooltip :content="t('bi.dash.preview')" placement="top">
              <el-button size="small" link :icon="View" @click="openView(row as unknown as BiDashboardDto)" />
            </el-tooltip>

            <el-tooltip v-if="auth.hasPerm('bi:dashboard:create')" :content="t('bi.dash.copy')" placement="top">
              <el-button size="small" link :icon="DocumentCopy" @click="onCopy(row as unknown as BiDashboardDto)" />
            </el-tooltip>

            <el-tooltip
              v-if="auth.hasPerm('bi:dashboard:publish')"
              :content="t(row.status === 1 ? 'bi.dash.unpublish' : 'bi.dash.publish')"
              placement="top"
            >
              <el-button
                size="small"
                link
                :icon="row.status === 1 ? 'Hide' : 'Promotion'"
                @click="onTogglePublish(row as unknown as BiDashboardDto)"
              />
            </el-tooltip>

            <!-- 危险操作（删除）收进 ··· 下拉 -->
            <el-dropdown
              v-if="auth.hasPerm('bi:dashboard:delete')"
              trigger="click"
              @command="(cmd: string) => cmd === 'delete' && onDelete(row as unknown as BiDashboardDto)"
            >
              <el-button :icon="MoreFilled" size="small" link />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="delete" divided>
                    <span class="danger-text">
                      <el-icon><Delete /></el-icon>{{ t('common.delete') }}
                    </span>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty :description="t('bi.dash.empty')" />
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10,20,50,100]"
        layout="total,sizes,prev,pager,next,jumper"
        @current-change="load"
        @size-change="onSizeChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  Delete, DocumentCopy, Edit, MoreFilled, Plus, Promotion, View, Files,
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { biApi, type BiDashboardDto } from '@/api/bi';
import { formatDateTime } from '@/utils/time';
import { openInNewTab } from '@/utils/openInNewTab';

const { t } = useI18n();
const router = useRouter();
const auth = useAuthStore();

const rows = ref<BiDashboardDto[]>([]);
const loading = ref(false);
const total = ref(0);

/** 分页状态：后端真分页，size 必须是 ref（const 无法 v-model:page-size） */
const query = reactive({ page: 1, size: 20 });

function formatTime(s?: string) { return formatDateTime(s); }

async function load() {
  loading.value = true;
  try {
    const r = await biApi.list(query);
    rows.value = r.items;
    total.value = r.total;
  } finally {
    loading.value = false;
  }
}

/** 切每页条数必须回到第 1 页：停在后面的页码改 size 会落到一个可能已不存在的页 */
function onSizeChange() {
  query.page = 1;
  load();
}

/**
 * 删掉本页最后一条时把页码退一格，否则停在一个空页上。
 *
 * <p>判断用 `<= 1` 而不是 `=== 0`：remove 返回后，被删的那一行还留在内存数组里。
 */
function reloadAfterDelete() {
  if (rows.value.length <= 1 && query.page > 1) query.page -= 1;
  load();
}

function openCreate() {
  router.push('/bi/dashboard/design/new');
}
function openEdit(row: BiDashboardDto) {
  router.push(`/bi/dashboard/design/${row.id}`);
}
/**
 * 「预览」在新标签页打开，不把当前列表页换掉 ——
 * 预览的典型动作是「边看边回到列表继续挑」，同 tab 跳走会丢掉列表的滚动位置和筛选态。
 * 弹窗被浏览器拦截时退回当前 tab，功能不中断。
 */
function openView(row: BiDashboardDto) {
  const path = `/bi/dashboard/view/${row.id}`;
  openInNewTab(path, { onBlocked: () => router.push(path) }, router);
}
function openWall() {
  router.push('/bi/dashboard/wall');
}

async function onCopy(row: BiDashboardDto) {
  try {
    const created = await biApi.copy(row.id);
    ElMessage.success(t('bi.dash.copyDone', { name: created.name }));
    load();
  } catch { /* http 已提示 */ }
}

async function onTogglePublish(row: BiDashboardDto) {
  if (row.status === 1) await biApi.unpublish(row.id);
  else await biApi.publish(row.id);
  load();
}

async function onDelete(row: BiDashboardDto) {
  try {
    await ElMessageBox.confirm(
      t('bi.dash.confirmDelete', { name: row.name }),
      t('common.confirmDelete'),
      {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel'),
      }
    );
    await biApi.remove(row.id);
    ElMessage.success(t('bi.dash.deleted'));
    reloadAfterDelete();
  } catch { /* 用户取消 */ }
}

onMounted(load);
</script>

<style scoped>
/* 双层吸顶（AGENTS §7.2）：不要给 wrap 加 overflow:hidden，否则 th sticky 失效 */
.bi-page .page-header {
  position: sticky;
  top: 0;
  z-index: 30;
  background: var(--color-bg);
}
.bi-table-wrap {
  margin-top: 16px;
}
:deep(.bi-table-wrap th.el-table__cell) {
  position: sticky;
  top: 92px;
  z-index: 20;
  background: var(--color-bg) !important;
}
.name-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.name-text {
  font-weight: 600;
  color: var(--color-text);
}
.name-code {
  font-size: 12px;
  color: var(--color-text-muted);
  font-family: 'SF Mono', 'JetBrains Mono', Menlo, monospace;
}
/* 状态在布局中表达：左侧竖条 + 文字，不用 pill / tag（AGENTS §7） */
.status-mark {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--color-text-secondary);
  padding-left: 8px;
  border-left: 2px solid var(--color-border-strong);
}
.status-mark.on {
  color: var(--color-success);
  border-left-color: var(--color-success);
}
.dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
}
.danger-text {
  color: var(--color-danger);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
</style>
