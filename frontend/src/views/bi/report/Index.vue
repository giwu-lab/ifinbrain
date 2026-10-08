<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Delete, DocumentCopy, Edit, MoreFilled, Plus, View } from '@element-plus/icons-vue';
import { reportApi, type Report } from '@/api/report';
import { useAuthStore } from '@/stores/auth';
import { openInNewTab } from '@/utils/openInNewTab';

const { t } = useI18n();
const router = useRouter();
const auth = useAuthStore();

const rows = ref<Report[]>([]);
const loading = ref(false);
const total = ref(0);

/** 分页状态：后端真分页，size 必须是 ref（const 无法 v-model:page-size） */
const query = reactive({ page: 1, size: 20 });

async function load() {
  loading.value = true;
  try {
    const r = await reportApi.list(query);
    rows.value = r.items;
    total.value = r.total;
  } finally {
    loading.value = false;
  }
}

/** 切每页条数必须回到第 1 页：停在第 5 页改 size 会落到一个可能已不存在的页 */
function onSizeChange() {
  query.page = 1;
  load();
}

/**
 * 删掉本页最后一条时把页码退一格，否则停在一个空页上。
 *
 * <p>判断用 `<= 1` 而不是 `=== 0`：remove 返回后，被删的那一行还留在内存数组里，
 * 页面上原本只有 1 行时 `length` 仍是 1。删完就空了，所以条件要含 1。
 */
function syncPageAfterDelete() {
  if (rows.value.length <= 1 && query.page > 1) {
    query.page -= 1;
  }
  return load();
}

onMounted(load);

function onCreate() {
  router.push('/bi/report/design/new');
}

function onDesign(row: Report) {
  router.push(`/bi/report/design/${row.id}`);
}

/**
 * 「查看」在新标签页打开，不把当前列表页换掉 ——
 * 报表要来回切参数比对，同 tab 跳走会丢掉列表的滚动位置和筛选态。
 * 弹窗被浏览器拦截时退回当前 tab，功能不中断。
 */
function onView(row: Report) {
  const path = `/bi/report/view/${row.id}`;
  openInNewTab(path, { onBlocked: () => router.push(path) }, router);
}

async function onCmd(cmd: string, row: Report) {
  try {
    if (cmd === 'copy') {
      await reportApi.copy(row.id);
      await load();
      return;
    }
    if (cmd === 'publish') {
      await reportApi.publish(row.id);
      await load();
      return;
    }
    if (cmd === 'unpublish') {
      await reportApi.unpublish(row.id);
      await load();
      return;
    }
    if (cmd === 'delete') {
      await ElMessageBox.confirm(
        t('report.confirmDelete', { name: row.name }),
        t('common.confirmDelete'),
        { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') }
      );
      await reportApi.remove(row.id);
      await syncPageAfterDelete();
    }
  } catch {
    /* 用户取消 */
  }
}
</script>

<template>
  <div class="page-container fade-in-up rp-page">
    <div class="table-wrap rp-table-wrap">
      <div class="table-actions">
        <el-button v-if="auth.hasPerm('report:create')" type="primary" :icon="Plus" @click="onCreate">
          {{ t('report.create') }}
        </el-button>
      </div>
      <el-table :data="rows" v-loading="loading" border stripe size="small">
        <el-table-column :label="t('report.col.name')" min-width="180">
          <template #default="{ row }">
            <div class="rp-name">{{ row.name }}</div>
            <div class="rp-code mono">{{ row.code }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('report.col.query')" min-width="150">
          <template #default="{ row }">{{ row.queryName || `#${row.queryId}` }}</template>
        </el-table-column>
        <el-table-column :label="t('report.col.pageSize')" width="110" align="right">
          <template #default="{ row }">
            <span class="tabular">{{ row.pageSize }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('report.col.maxRows')" width="120" align="right">
          <template #default="{ row }">
            <span class="tabular">{{ row.maxRows > 0 ? row.maxRows : '∞' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('report.col.status')" width="90" align="center">
          <template #default="{ row }">
            <span :class="row.status === 1 ? 'rp-status-on' : 'rp-status-off'">
              {{ row.status === 1 ? t('report.published') : t('report.draft') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('report.col.updatedAt')" width="170">
          <template #default="{ row }">
            <span class="mono-time">{{ (row.updatedAt || '').replace('T', ' ').slice(0, 19) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="160" align="center" fixed="right">
          <template #default="{ row }">
            <!-- el-table 的 slot row 是 DefaultRow，取值时断言成 Report（列已由 :data 的类型约束） -->
            <el-tooltip :content="t('report.view')" placement="top">
              <el-button size="small" link :icon="View" @click="onView(row as Report)" />
            </el-tooltip>
            <el-tooltip v-if="auth.hasPerm('report:update')" :content="t('report.design')" placement="top">
              <el-button size="small" link :icon="Edit" @click="onDesign(row as Report)" />
            </el-tooltip>
            <el-dropdown trigger="click" @command="(cmd: string) => onCmd(cmd, row as Report)">
              <el-button :icon="MoreFilled" size="small" link />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-if="auth.hasPerm('report:create')" command="copy">
                    <el-icon><DocumentCopy /></el-icon>{{ t('report.copy') }}
                  </el-dropdown-item>
                  <el-dropdown-item
                    v-if="auth.hasPerm('report:publish')"
                    :command="row.status === 1 ? 'unpublish' : 'publish'"
                    divided
                  >
                    {{ row.status === 1 ? t('report.unpublish') : t('report.publish') }}
                  </el-dropdown-item>
                  <el-dropdown-item v-if="auth.hasPerm('report:delete')" command="delete" divided>
                    <span class="danger-text">
                      <el-icon><Delete /></el-icon>{{ t('common.delete') }}
                    </span>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
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

<style scoped>
.rp-page .page-header {
  position: sticky;
  top: 0;
  z-index: 30;
  background: var(--color-bg);
}

.rp-table-wrap {
  margin-top: 16px;
}

.rp-name {
  font-weight: 600;
  color: var(--color-text);
}

.rp-code {
  font-size: 12px;
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
}

.rp-status-on {
  color: var(--color-success);
  font-size: var(--font-size-sm);
}

.rp-status-off {
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}
</style>
