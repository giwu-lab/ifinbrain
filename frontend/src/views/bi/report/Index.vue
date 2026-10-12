<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Delete, DocumentCopy, Edit, Hide, Lock, MoreFilled, Plus, Promotion } from '@element-plus/icons-vue';
import { reportApi, type PublishDir, type Report } from '@/api/report';
import { useAuthStore } from '@/stores/auth';
import { resolveMenuName } from '@/utils/menu';
import { openInNewTab } from '@/utils/openInNewTab';
import { formatDateTime } from '@/utils/time';
import PermMatrixDialog from '@/components/PermMatrixDialog.vue';
import type { PermVerbSpec } from '@/components/permMatrixTypes';

const { t } = useI18n();
const router = useRouter();
const auth = useAuthStore();

const rows = ref<Report[]>([]);
const loading = ref(false);
const total = ref(0);

/** 分页状态：后端真分页，size 必须是 ref（const 无法 v-model:page-size） */
const query = reactive({ page: 1, size: 20 });

/** 发布对话框 */
const pubVisible = ref(false);
const pubSaving = ref(false);
const pubDirs = ref<PublishDir[]>([]);
const pubForm = reactive({ id: 0, name: '', parentId: undefined as number | undefined, icon: 'Tickets', sort: 99 });

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

/**
 * 目录下拉的名字走 i18n —— `sys_menu.name` 存的是给管理员看的英文原文
 * （Invoice / BI Analytics…），直接显示会和中文界面割裂，
 * 也会和侧边栏显示的名字对不上。用与侧边栏同一套 key 解析。
 */
function dirName(d: PublishDir) {
  return resolveMenuName({ name: d.name, path: d.path }, t);
}

/**
 * 已发布报表对应的侧边栏菜单项（从 auth.menus 里按 path 反查）。
 *
 * <p>path 就是播放页路由 `/bi/report/view/{id}`，与后端 MenuPublisher 用的判据一致 ——
 * 不另加接口，菜单树本来就在登录时拉回来了。
 */
function publishedMenu(row: Report) {
  const path = `/bi/report/view/${row.id}`;
  return (auth.menus || []).find((m) => m.path === path);
}

/**
 * 发布：在菜单树里挂一个入口。
 *
 * <p><b>菜单名的回填规则</b>：
 * 首次发布回填**报表名**（没别的名字可用，菜单得有个名字）；
 * 已发布过则回填**当前侧边栏上的那个名字** —— 否则用户上次特意改的名字会被
 * 报表名悄悄顶掉，且对话框里显示的也不是侧边栏实际显示的东西，两边对不上。
 *
 * <p>表单字段一律允许留空 —— 留空即「沿用上次的配置」，后端只覆盖真正带了的字段。
 */
async function openPublish(row: Report) {
  const m = publishedMenu(row);
  pubForm.id = row.id;
  pubForm.name = m?.name || row.name;
  pubForm.icon = m?.icon || 'Tickets';
  pubForm.sort = m?.sort ?? 99;
  pubForm.parentId = m?.parentId ?? undefined;
  pubVisible.value = true;
  if (!pubDirs.value.length) {
    try {
      pubDirs.value = await reportApi.publishDirs();
    } catch {
      /* 拉不到目录就让用户只能选默认，不阻断发布 */
    }
  }
}

async function confirmPublish() {
  pubSaving.value = true;
  try {
    // 只把「用户真的填了」的字段发过去：空串 / undefined 一律不传
    const body = {
      parentId: pubForm.parentId,
      menuName: pubForm.name.trim() || undefined,
      icon: pubForm.icon || undefined,
      sort: pubForm.sort,
    };
    await reportApi.publish(pubForm.id, body);
    pubVisible.value = false;
    ElMessage.success(t('report.published'));
    await load();
    // 侧边栏菜单变了，当前用户的菜单树要重新拉，否则新入口不会立刻出现
    await auth.fetchMe();
  } catch (e: unknown) {
    ElMessage.error(t('report.publishFailed') + ': ' + (e instanceof Error ? e.message : String(e)));
  } finally {
    pubSaving.value = false;
  }
}

async function onUnpublish(row: Report) {
  try {
    await ElMessageBox.confirm(
      t('report.confirmUnpublish', { name: row.name }),
      t('report.unpublish'),
      { type: 'warning', confirmButtonText: t('common.ok'), cancelButtonText: t('common.cancel') }
    );
    await reportApi.unpublish(row.id);
    await load();
    await auth.fetchMe();
  } catch {
    /* 用户取消 */
  }
}

/** 复制：与发布同一档权限（能建报表就能复制一份草稿） */
async function onCopy(row: Report) {
  try {
    const created = await reportApi.copy(row.id);
    ElMessage.success(t('report.copyDone', { name: created.name }));
    await load();
  } catch { /* http 已提示 */ }
}

/**
 * 发布按钮的正反面：草稿 → 弹对话框选目录；已发布 → 确认后下线。
 *
 * <p>它们本来就是一个动作，只是方向不同，所以共用一个图标位而不是两个。
 */
function onPublishClick(row: Report) {
  if (row.status === 1) return onUnpublish(row);
  return openPublish(row);
}

/** 4 个实体级动作 —— 与后端 EntityAuthSupport.VERBS 一一对应 */
const PERM_VERBS: PermVerbSpec[] = [
  { key: 'view', label: t('documents.perm.view') },
  { key: 'design', label: t('documents.perm.design') },
  { key: 'publish', label: t('documents.perm.publish') },
  { key: 'delete', label: t('documents.perm.delete') },
];

/** 授权对话框状态 */
const permDialog = reactive({ visible: false, id: 0, code: '', name: '' });

function openPerms(row: Report) {
  permDialog.id = row.id;
  permDialog.code = row.code;
  permDialog.name = row.name;
  permDialog.visible = true;
}

/** ··· 下拉的命令分发 */
function onMore(cmd: string, row: Report) {
  if (cmd === 'perms') openPerms(row);
  else if (cmd === 'delete') onDelete(row);
}

async function onDelete(row: Report) {
  try {
    await ElMessageBox.confirm(
      t('report.confirmDelete', { name: row.name }),
      t('common.confirmDelete'),
      { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') }
    );
    await reportApi.remove(row.id);
    await syncPageAfterDelete();
    // 菜单被连带下线了，当前用户的菜单树要重新拉
    await auth.fetchMe();
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
            <!--
              报表名即播放页入口（新标签页）。
              操作列只放 设计 / 复制 / 发布 / 删除 四个命令，
              「查看」不再占一个图标位 —— 名称可点就够了，占位留给编辑类操作。
            -->
            <el-tooltip :content="t('report.view')" placement="top">
              <span class="rp-name rp-name-link" @click="onView(row as Report)">{{ row.name }}</span>
            </el-tooltip>
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
            <span class="mono-time">{{ formatDateTime(row.updatedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="180" align="center" fixed="right">
          <template #default="{ row }">
            <!--
              高频命令图标直显 + tooltip；`···` 里只留删除（AGENTS §8.2）。
              ··· 只剩一项时用 dropdown 反而多一次点击，所以删除直接放最后一个图标位。
            -->
            <el-tooltip v-if="auth.hasPerm('report:update')" :content="t('report.design')" placement="top">
              <el-button size="small" link :icon="Edit" @click="onDesign(row as Report)" />
            </el-tooltip>

            <el-tooltip v-if="auth.hasPerm('report:create')" :content="t('report.copy')" placement="top">
              <el-button size="small" link :icon="DocumentCopy" @click="onCopy(row as Report)" />
            </el-tooltip>

            <el-tooltip
              v-if="auth.hasPerm('report:publish')"
              :content="t(row.status === 1 ? 'report.unpublish' : 'report.publish')"
              placement="top"
            >
              <el-button
                size="small"
                link
                :icon="row.status === 1 ? Hide : Promotion"
                @click="onPublishClick(row as Report)"
              />
            </el-tooltip>

            <el-dropdown
              v-if="auth.hasPerm('report:delete') || auth.hasPerm('report:update')"
              trigger="click"
              @command="(cmd: string) => onMore(cmd, row as Report)"
            >
              <el-button :icon="MoreFilled" size="small" link />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-if="auth.hasPerm('report:update')" command="perms">
                    <span class="more-text">
                      <el-icon><Lock /></el-icon>{{ t('report.auth') }}
                    </span>
                  </el-dropdown-item>
                  <el-dropdown-item v-if="auth.hasPerm('report:delete')" command="delete">
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

    <!--
      发布对话框。
      字段全部可留空：留空 = 沿用上次的配置（后端只覆盖真正带了的字段），
      所以「重新发布」不会把用户调好的目录和菜单名弹回默认值。
    -->
    <el-dialog v-model="pubVisible" :title="t('report.publish')" width="480px">
      <el-form label-width="88px" label-position="left">
        <el-form-item :label="t('report.pubDir')">
          <el-select v-model="pubForm.parentId" :placeholder="t('report.pubDirPlaceholder')" clearable style="width: 100%">
            <el-option v-for="d in pubDirs" :key="d.id" :label="dirName(d)" :value="d.id" />
          </el-select>
          <div class="rpd-hint">{{ t('report.pubDirHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('report.pubMenuName')">
          <el-input v-model="pubForm.name" :placeholder="t('report.pubMenuNamePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('report.pubSort')">
          <el-input-number v-model="pubForm.sort" :min="0" :max="9999" controls-position="right" />
        </el-form-item>
        <el-form-item :label="t('report.pubPerm')">
          <!-- 权限码是标识符不是文案，不进 i18n（AGENTS §2：i18n 只管面向用户的文字） -->
          <span class="mono-perm">report:view</span>
          <div class="rpd-hint">{{ t('report.pubPermHint') }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pubVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="pubSaving" @click="confirmPublish">
          {{ t('report.publish') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 实体级授权：谁能看 / 能改 / 能发布 / 能删这张报表 -->
    <PermMatrixDialog
      v-model="permDialog.visible"
      :title="t('report.authDialogTitle', { name: permDialog.name })"
      :code-prefix="`report:${permDialog.code}:`"
      :verbs="PERM_VERBS"
      :entity-id="permDialog.id"
      :load="reportApi.permissions"
      :save="reportApi.updatePermissions"
      @saved="load"
    />
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

/*
  报表名 = 播放页入口。
  用主色 + hover 下划线而不是按钮样式：它在「名称」列里就该读成标题，
  做成按钮会和右边的操作图标抢视觉层级。
*/
.rp-name-link {
  color: var(--color-primary);
  cursor: pointer;
  border-bottom: 1px solid transparent;
  transition: border-color 0.15s;
}
.rp-name-link:hover {
  border-bottom-color: var(--color-primary);
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

/* 发布对话框里的说明文字 */
.rpd-hint {
  font-size: 12px;
  color: var(--color-text-muted);
  line-height: 1.5;
}

/* 权限码：等宽字体，让人一眼看出这是个标识符而不是按钮 */
.mono-perm {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}
</style>
