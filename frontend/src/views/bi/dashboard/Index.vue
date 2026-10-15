<template>
  <div class="page-container fade-in-up bi-page">
    <div class="table-wrap bi-table-wrap">
      <div class="table-actions">
        <!-- 主操作（新建）在左，次要操作（展示墙）靠右 —— 全项目统一，见 global.css .table-actions -->
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
            <!-- 「名称 + 代码」两行主标识单元格，样式见 global.css 的 .nc-cell -->
            <div class="nc-cell">
              <!--
                大屏名即播放页入口（新标签页）。
                操作列只放 设计 / 复制 / 发布 / 删除 四个命令，
                「预览」不再占一个图标位 —— 名称可点就够了。
              -->
              <el-tooltip :content="t('bi.dash.preview')" placement="top">
                <span class="nc-name nc-link" @click="openView(row as unknown as BiDashboardDto)">
                  {{ row.name }}
                </span>
              </el-tooltip>
              <code class="nc-code">{{ row.code }}</code>
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
          width="180"
          align="center"
          fixed="right"
        >
          <template #default="{ row }">
            <!--
              高频命令图标直显 + tooltip；`···` 里留「授权 / 删除」两项（AGENTS §8.2）。
              与报表列表保持同一套「设计 / 复制 / 发布 / ···」四命令。
            -->
            <el-tooltip v-if="auth.hasPerm('bi:dashboard:update')" :content="t('bi.dash.edit')" placement="top">
              <el-button size="small" link :icon="Edit" @click="openEdit(row as unknown as BiDashboardDto)" />
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
                :icon="row.status === 1 ? Hide : Promotion"
                @click="onPublishClick(row as unknown as BiDashboardDto)"
              />
            </el-tooltip>

            <!-- 危险操作（删除）与授权管理收进 ··· 下拉；高频的四个命令直显 -->
            <el-dropdown
              v-if="auth.hasPerm('bi:dashboard:delete') || auth.hasPerm('bi:dashboard:update')"
              trigger="click"
              @command="(cmd: string) => onMore(cmd, row as unknown as BiDashboardDto)"
            >
              <el-button :icon="MoreFilled" size="small" link />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-if="auth.hasPerm('bi:dashboard:update')" command="perms">
                    <span class="more-text">
                      <el-icon><Lock /></el-icon>{{ t('bi.dash.auth') }}
                    </span>
                  </el-dropdown-item>
                  <el-dropdown-item v-if="auth.hasPerm('bi:dashboard:delete')" command="delete">
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

    <!--
      发布对话框。字段全部可留空：留空 = 沿用上次的配置（后端只覆盖真正带了的字段），
      所以「重新发布」不会把用户调好的目录和菜单名弹回默认值。
      与报表发布对话框同构 —— 两个模块的发布语义必须一致。
    -->
    <el-dialog v-model="pubVisible" :title="t('bi.dash.publish')" width="480px">
      <el-form label-width="88px" label-position="left">
        <el-form-item :label="t('bi.dash.pubDir')">
          <el-select v-model="pubForm.parentId" :placeholder="t('bi.dash.pubDirPlaceholder')" clearable style="width: 100%">
            <el-option v-for="d in pubDirs" :key="d.id" :label="dirName(d)" :value="d.id" />
          </el-select>
          <div class="bi-hint">{{ t('bi.dash.pubDirHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('bi.dash.pubMenuName')">
          <el-input v-model="pubForm.name" :placeholder="t('bi.dash.pubMenuNamePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('bi.dash.pubSort')">
          <el-input-number v-model="pubForm.sort" :min="0" :max="9999" controls-position="right" />
        </el-form-item>
        <el-form-item :label="t('bi.dash.pubPerm')">
          <!-- 权限码是标识符不是文案，不进 i18n（AGENTS §2：i18n 只管面向用户的文字） -->
          <span class="mono-perm">bi:dashboard:view</span>
          <div class="bi-hint">{{ t('bi.dash.pubPermHint') }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pubVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="pubSaving" @click="confirmPublish">
          {{ t('bi.dash.publish') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 实体级授权：谁能看 / 能改 / 能发布 / 能删这张大屏 -->
    <PermMatrixDialog
      v-model="permDialog.visible"
      :title="t('bi.dash.authDialogTitle', { name: permDialog.name })"
      :code-prefix="`bi:dashboard:${permDialog.code}:`"
      :verbs="PERM_VERBS"
      :entity-id="permDialog.id"
      :load="biApi.permissions"
      :save="biApi.updatePermissions"
      @saved="load"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  Delete, DocumentCopy, Edit, Hide, Lock, MoreFilled, Plus, Promotion,
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { biApi, type BiDashboardDto } from '@/api/bi';
import type { PublishDir } from '@/api/publishMenu';
import { resolveMenuName } from '@/utils/menu';
import { formatDateTime } from '@/utils/time';
import { openInNewTab } from '@/utils/openInNewTab';
import PermMatrixDialog from '@/components/PermMatrixDialog.vue';
import type { PermVerbSpec } from '@/components/permMatrixTypes';

const { t } = useI18n();
const router = useRouter();
const auth = useAuthStore();

const rows = ref<BiDashboardDto[]>([]);
const loading = ref(false);
const total = ref(0);

/** 分页状态：后端真分页，size 必须是 ref（const 无法 v-model:page-size） */
const query = reactive({ page: 1, size: 20 });

/** 发布对话框 */
const pubVisible = ref(false);
const pubSaving = ref(false);
const pubDirs = ref<PublishDir[]>([]);
const pubForm = reactive({ id: 0, name: '', parentId: undefined as number | undefined, icon: 'Tickets', sort: 99 });

/** 4 个实体级动作 —— 与后端 EntityAuthSupport.VERBS 一一对应 */
const PERM_VERBS: PermVerbSpec[] = [
  { key: 'view', label: t('documents.perm.view') },
  { key: 'design', label: t('documents.perm.design') },
  { key: 'publish', label: t('documents.perm.publish') },
  { key: 'delete', label: t('documents.perm.delete') },
];

/** 授权对话框状态 */
const permDialog = reactive({ visible: false, id: 0, code: '', name: '' });

function openPerms(row: BiDashboardDto) {
  permDialog.id = row.id;
  permDialog.code = row.code;
  permDialog.name = row.name;
  permDialog.visible = true;
}

/** ··· 下拉的命令分发 */
function onMore(cmd: string, row: BiDashboardDto) {
  if (cmd === 'perms') openPerms(row);
  else if (cmd === 'delete') onDelete(row);
}

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

async function onCopy(row: BiDashboardDto) {
  try {
    const created = await biApi.copy(row.id);
    ElMessage.success(t('bi.dash.copyDone', { name: created.name }));
    load();
  } catch { /* http 已提示 */ }
}

/**
 * 发布按钮分流：草稿走对话框选目录，已发布走二次确认后下线。
 *
 * <p>两件事合成一个图标不是省事 —— 它们本来就是一个动作的正反面：
 * 「发布」= 上线 + 建侧边栏入口，「取消发布」= 下线 + 入口软下线。
 */
async function onPublishClick(row: BiDashboardDto) {
  if (row.status === 1) {
    try {
      await ElMessageBox.confirm(
        t('bi.dash.confirmUnpublish', { name: row.name }),
        t('bi.dash.unpublish'),
        { type: 'warning', confirmButtonText: t('bi.dash.unpublish'), cancelButtonText: t('common.cancel') }
      );
      await biApi.unpublish(row.id);
      ElMessage.success(t('bi.dash.unpublished2'));
      await load();
      // 侧边栏菜单变了，当前用户的菜单树要重新拉，否则下线后入口还挂着
      await auth.fetchMe();
    } catch { /* 用户取消 */ }
    return;
  }
  openPublish(row);
}

/**
 * 目录下拉的名字走 i18n —— `sys_menu.name` 存的是给管理员看的英文原文
 * （Invoice / BI Analytics…），直接显示会和中文界面割裂，
 * 也会和侧边栏显示的名字对不上。用与侧边栏同一套 key 解析。
 */
function dirName(d: PublishDir) {
  return resolveMenuName({ name: d.name, path: d.path }, t);
}

/** 已发布大屏对应的侧边栏菜单项（按 path 反查，判据与后端 MenuPublisher 一致） */
function publishedMenu(row: BiDashboardDto) {
  const path = `/bi/dashboard/view/${row.id}`;
  return (auth.menus || []).find((m) => m.path === path);
}

/**
 * 发布：在菜单树里挂一个入口。
 *
 * <p><b>菜单名的回填规则</b>：首次发布回填**大屏名**；已发布过则回填**当前侧边栏上的
 * 那个名字** —— 否则用户上次特意改的名字会被大屏名悄悄顶掉，对话框里显示的
 * 也不是侧边栏实际显示的东西，两边对不上。
 *
 * 表单字段一律允许留空 —— 留空即「沿用上次的配置」，后端只覆盖真正带了的字段。
 * 所以「重新发布已发布的大屏」不会把用户调好的目录和显示名悄悄弹回去。
 */
async function openPublish(row: BiDashboardDto) {
  const m = publishedMenu(row);
  pubForm.id = row.id;
  pubForm.name = m?.name || row.name;
  pubForm.icon = m?.icon || 'Tickets';
  pubForm.sort = m?.sort ?? 99;
  pubForm.parentId = m?.parentId ?? undefined;
  pubVisible.value = true;
  if (!pubDirs.value.length) {
    try {
      pubDirs.value = await biApi.publishDirs();
    } catch {
      /* 拉不到目录就让用户只能选默认，不阻断发布 */
    }
  }
}

async function confirmPublish() {
  pubSaving.value = true;
  try {
    // 只把「用户真的填了」的字段发过去：空串 / undefined 一律不传
    await biApi.publish(pubForm.id, {
      parentId: pubForm.parentId,
      menuName: pubForm.name.trim() || undefined,
      icon: pubForm.icon || undefined,
      sort: pubForm.sort,
    });
    pubVisible.value = false;
    ElMessage.success(t('bi.dash.published2'));
    await load();
    // 新入口不会立刻出现，除非当前用户的菜单树重新拉一次
    await auth.fetchMe();
  } catch { /* http 已提示 */ }
  finally {
    pubSaving.value = false;
  }
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
.bi-table-wrap {
  margin-top: 16px;
}
:deep(.bi-table-wrap th.el-table__cell) {
  position: sticky;
  top: 92px;
  z-index: 20;
  background: var(--color-bg) !important;
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
/* 发布对话框里的说明文字 */
.bi-hint {
  font-size: 12px;
  color: var(--color-text-muted);
  line-height: 1.5;
}
/* 权限码：等宽字体，让人一眼看出这是个标识符而不是按钮 */
.mono-perm {
  font-family: var(--font-mono);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}
</style>
