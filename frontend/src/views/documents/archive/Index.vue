<template>
  <div class="page-container fade-in-up cat-page">
    <div class="table-wrap cat-table-wrap">
      <div class="table-actions">
        <el-button
          v-if="auth.hasPerm('documents:upload')"
          type="primary"
          :icon="Plus"
          @click="openCreate()"
        >
          {{ t('documents.createArchive') }}
        </el-button>
      </div>
      <!-- 表格 + 分页作为一组：当前页有数据才渲染，翻到空页时退化成下面的空态 -->
      <template v-if="rows.length > 0">
      <el-table
        :data="rows"
        v-loading="loading"
        border
        stripe
        size="small"
        row-key="id"
      >
        <!--
          「名称 + 路径标识」合并成一列（AGENTS §8.1.4）：两者是同一实体的主键/别名，
          分列会让人左右扫视去对齐同一行。图标随名称走，仍在主行。
        -->
        <el-table-column :label="t('documents.colArchiveName')" min-width="240">
          <template #default="{ row }">
            <div class="nc-cell">
              <span class="nc-name archive-name-row">
                <el-icon v-if="(row as ArchiveLibrary).icon" class="name-icon">
                  <component :is="resolveIcon((row as ArchiveLibrary).icon)" />
                </el-icon>
                {{ (row as ArchiveLibrary).name }}
              </span>
              <code class="nc-code">{{ (row as ArchiveLibrary).slug }}</code>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('documents.colArchiveIcon')" prop="icon" width="100">
          <template #default="{ row }">
            <span class="muted">{{ (row as ArchiveLibrary).icon || 'Folder' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('documents.colArchiveSort')" prop="sort" width="80" align="right" />
        <el-table-column :label="t('documents.colArchiveStatus')" width="90" align="center">
          <template #default="{ row }">
            <span class="status-pill" :class="(row as ArchiveLibrary).status === 1 ? 'on' : 'off'">
              {{ (row as ArchiveLibrary).status === 1 ? t('common.enabled') : t('common.disabled') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('documents.colArchiveCount')" width="100" align="right">
          <template #default="{ row }">
            <span class="count-cell">{{ ((row as ArchiveLibrary).fileCount ?? 0) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="160" align="center" fixed="right">
          <template #default="{ row }">
            <!-- 编辑：图标直显 -->
            <el-tooltip v-if="auth.hasPerm('documents:update')" :content="t('common.edit')" placement="top">
              <el-button size="small" link :icon="Edit" @click="openEdit(row as ArchiveLibrary)" />
            </el-tooltip>
            <!-- 授权：图标直显 -->
            <el-tooltip v-if="auth.hasPerm('documents:upload')" :content="t('documents.colAuth')" placement="top">
              <el-button size="small" link :icon="Key" @click="openPerms(row as ArchiveLibrary)" />
            </el-tooltip>
            <!-- 删除：保留在 ··· 下拉 -->
            <el-dropdown v-if="auth.hasPerm('documents:delete')" trigger="click" @command="(cmd: string) => onCmd(cmd, row as ArchiveLibrary)">
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
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10,20,50,100]"
        layout="total,sizes,prev,pager,next,jumper"
        @current-change="reload"
        @size-change="onSizeChange"
      />
      </template>

      <!-- 空状态：无档案库时居中展示，引导用户创建第一个。
           判据是 total 而不是 rows.length：翻到后面的空页 ≠ 库里没档案库 -->
      <div v-else-if="!loading && total === 0" class="empty-state">
        <el-icon class="empty-icon"><Folder /></el-icon>
        <h2 class="empty-title">{{ t('documents.archiveEmptyTitle') }}</h2>
        <p class="empty-desc muted">{{ t('documents.archiveEmptyDesc') }}</p>
        <el-button
          v-if="auth.hasPerm('documents:upload')"
          type="primary"
          :icon="Plus"
          @click="openCreate()"
        >
          {{ t('documents.createArchive') }}
        </el-button>
      </div>
    </div>

    <!-- 新建 / 编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editing ? t('documents.editArchive') : t('documents.createArchive')"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" @submit.prevent>
        <el-form-item :label="t('documents.colArchiveName')" prop="name">
          <el-input v-model="form.name" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item :label="t('documents.colArchiveSlug')" prop="slug">
          <el-input
            v-model="form.slug"
            :placeholder="t('documents.archiveSlugHint')"
            maxlength="64"
          />
          <div class="form-hint muted">{{ t('documents.archiveSlugHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('documents.colArchiveIcon')">
          <IconPicker v-model="form.icon" />
        </el-form-item>
        <el-form-item :label="t('documents.colArchiveSort')">
          <el-input-number v-model="form.sort" :min="0" :max="9999" controls-position="right" />
        </el-form-item>
        <el-form-item :label="t('common.status')">
          <el-switch
            v-model="form.status"
            :active-value="1"
            :inactive-value="0"
            inline-prompt
            :active-text="t('common.enabled')"
            :inactive-text="t('common.disabled')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="onSubmit">
          {{ t('common.save') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 档案库授权：行=角色，列=4 个 verb（view/upload/update/delete） -->
    <el-dialog
      v-model="permsDialog.visible"
      :title="permsDialog.title"
      width="640px"
      :close-on-click-modal="false"
      align-center
    >
      <div v-if="permsDialog.category" class="auth-header">
        <div class="auth-row">
          <span class="muted small">{{ t('documents.authCodeLabel') }}：</span>
          <code class="mono">{{ permsDialog.category.permissionCode }}</code>
        </div>
        <div class="form-hint muted">{{ t('documents.authRoleHint') }}</div>
      </div>

      <el-table
        :data="allRoles"
        v-loading="permsDialog.loading"
        border
        size="small"
        class="auth-grid"
        row-key="id"
      >
        <el-table-column :label="t('system.colCode')" min-width="140">
          <template #default="{ row }">
            <div class="auth-role-cell">
              <span class="strong">{{ row.name }}</span>
              <code class="mono small muted">{{ row.code }}</code>
              <el-tag v-if="isAdminRole(row as RoleItem)" size="small" type="warning" effect="plain" round>
                {{ t('system.roleAdminTag') }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column
          v-for="verb in PERM_VERBS"
          :key="verb"
          :label="t(`documents.perm.${verb}`)"
          align="center"
          width="120"
        >
          <template #header>
            <div class="auth-col-head">
              <span>{{ t(`documents.perm.${verb}`) }}</span>
              <el-button link size="small" @click="toggleColumn(verb, true)">
                {{ t('documents.authSelectAll') }}
              </el-button>
            </div>
          </template>
          <template #default="{ row }">
            <el-checkbox
              :model-value="hasGrant(row.id, verb)"
              :disabled="isAdminRole(row as RoleItem)"
              @change="toggleCell(row.id, verb, $event)"
            />
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <el-button @click="permsDialog.visible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="permsDialog.saving" @click="savePerms">
          {{ t('common.save') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { Folder, Plus, Edit, Delete, MoreFilled, Key } from '@element-plus/icons-vue';
import * as ElIcons from '@element-plus/icons-vue';
import { useI18n } from 'vue-i18n';
import { useAuthStore } from '@/stores/auth';
import IconPicker from '@/components/IconPicker.vue';
import {
  archiveApi,
  type ArchiveLibrary,
  type ArchivePermGrants,
} from '@/api/document';
import { roleApi, type RoleItem } from '@/api/system';

const { t } = useI18n();
const auth = useAuthStore();

/** 档案库 4 个权限 verb —— 与后端 ArchiveLibraryService.CAT_PERMS 对齐 */
const PERM_VERBS = ['view', 'upload', 'update', 'delete'] as const;
type PermVerb = typeof PERM_VERBS[number];

const loading = ref(false);
const rows = ref<ArchiveLibrary[]>([]);
const total = ref(0);

/** 分页状态：后端真分页，size 必须是 ref（const 无法 v-model:page-size） */
const query = reactive({ page: 1, size: 20 });
const dialogVisible = ref(false);
const saving = ref(false);
const editing = ref<ArchiveLibrary | null>(null);
const formRef = ref<FormInstance>();

const form = reactive<{
  name: string;
  slug: string;
  icon: string;
  sort: number;
  status: 0 | 1;
  remark?: string;
}>({
  name: '',
  slug: '',
  icon: 'Folder',
  sort: 0,
  status: 1,
  remark: undefined,
});

// ===================== 授权对话框 =====================

const allRoles = ref<RoleItem[]>([]);
/** 当前授权对话框正在编辑的档案库 */
const permsDialog = reactive<{
  visible: boolean;
  loading: boolean;
  saving: boolean;
  title: string;
  category: ArchiveLibrary | null;
}>({
  visible: false,
  loading: false,
  saving: false,
  title: '',
  category: null,
});
/** 当前档案库的 4 个 verb 授权状态：Map<roleId, Set<verb>> —— 勾选/取消都改这里 */
const grants = ref<Map<number, Set<PermVerb>>>(new Map());
/** 初始 grants 的快照（用于检测是否有改动） */
const initialGrants = ref<Map<number, Set<PermVerb>>>(new Map());

const SLUG_RE = /^[a-z0-9]+(-[a-z0-9]+)*$/;

const rules: FormRules = {
  name: [
    { required: true, message: t('common.required'), trigger: 'blur' },
    { max: 64, message: t('common.required'), trigger: 'blur' },
  ],
  slug: [
    { required: true, message: t('common.required'), trigger: 'blur' },
    {
      validator: (_r, v: string, cb) => {
        if (!v || !SLUG_RE.test(v.trim().toLowerCase())) {
          cb(new Error(t('documents.archiveSlugInvalid')));
        } else cb();
      },
      trigger: 'blur',
    },
  ],
};

function resolveIcon(name?: string) {
  if (!name) return ElIcons.Folder;
  return (ElIcons as Record<string, unknown>)[name] || ElIcons.Folder;
}

async function reload() {
  loading.value = true;
  try {
    const r = await archiveApi.page(query);
    rows.value = r.items;
    total.value = r.total;
  } catch {
    /* http interceptor already toasted */
  } finally {
    loading.value = false;
  }
}

/** 切每页条数必须回到第 1 页：停在后面的页码改 size 会落到一个可能已不存在的页 */
function onSizeChange() {
  query.page = 1;
  reload();
}

/**
 * 删掉本页最后一条时把页码退一格，否则停在一个空页上。
 * 判断用 `<= 1`：remove 返回后被删的那一行还留在内存数组里。
 */
async function reloadAfterDelete() {
  if (rows.value.length <= 1 && query.page > 1) query.page -= 1;
  await reload();
}

function openCreate() {
  editing.value = null;
  Object.assign(form, {
    name: '',
    slug: '',
    icon: 'Folder',
    sort: nextSort(),
    status: 1,
    remark: undefined,
  });
  dialogVisible.value = true;
}

function openEdit(row: ArchiveLibrary) {
  editing.value = row;
  Object.assign(form, {
    name: row.name,
    slug: row.slug,
    icon: row.icon || 'Folder',
    sort: row.sort ?? 0,
    status: row.status ?? 1,
    remark: row.remark,
  });
  dialogVisible.value = true;
}

function nextSort(): number {
  const max = rows.value.reduce((m, r) => Math.max(m, r.sort ?? 0), 0);
  return max + 1;
}

async function onSubmit() {
  if (!formRef.value) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;

  saving.value = true;
  try {
    const body = {
      name: form.name.trim(),
      slug: form.slug.trim().toLowerCase(),
      icon: form.icon || 'Folder',
      sort: form.sort,
      status: form.status,
      remark: form.remark,
    };
    if (editing.value) {
      await archiveApi.update(editing.value.id, body);
      ElMessage.success(t('documents.updated'));
    } else {
      await archiveApi.create(body);
      ElMessage.success(t('documents.created'));
    }
    dialogVisible.value = false;
    await reload();
    // 同步刷新左侧目录树（sys_menu 也改了 / 增了 / 删了；auth.menus 重新拉一次）
    await auth.fetchMe();
    // 关键：admin 的 4 个档案库权限是新建/改名档案库时自动 seed 的；
    // 旧 JWT 不带这些 perm → 立刻 refresh 一对，让浏览器侧 token 也带新 perm
    await auth.refreshTokens();
  } catch {
    /* http interceptor */
  } finally {
    saving.value = false;
  }
}

async function onDelete(row: ArchiveLibrary) {
  try {
    await archiveApi.remove(row.id);
    ElMessage.success(t('documents.deleteSuccess'));
    await reloadAfterDelete();
    await auth.fetchMe();
    await auth.refreshTokens();
  } catch (e: any) {
    // 服务端已传 400 + 业务消息（"档案库下仍有 N 个档案"），interceptor 已 toast
    // 但 BizException 400 仍会走到这里；前端无需再弹
    void e;
  }
}

/** ··· 下拉菜单统一入口 */
async function onCmd(cmd: string, row: ArchiveLibrary) {
  if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(
        t('documents.deleteCategoryConfirm', { name: row.name }),
        t('common.confirmDelete'),
        { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') }
      );
      await onDelete(row);
    } catch { /* 用户取消 */ }
  }
}

// ===================== 授权对话框逻辑 =====================

/** 判断一个角色是否是 admin —— admin 永远 4 权限勾选且不可取消 */
function isAdminRole(role: RoleItem): boolean {
  return role.code === 'admin';
}

function hasGrant(roleId: number, verb: PermVerb): boolean {
  return grants.value.get(roleId)?.has(verb) ?? false;
}

function setGrant(roleId: number, verb: PermVerb, on: boolean) {
  const set = grants.value.get(roleId) ?? new Set<PermVerb>();
  if (on) set.add(verb); else set.delete(verb);
  grants.value.set(roleId, set);
  // 触发响应式更新
  grants.value = new Map(grants.value);
}

/** 单格切换 */
function toggleCell(roleId: number, verb: PermVerb, on: boolean | string | number) {
  setGrant(roleId, verb, Boolean(on));
}

/** 行级全选/清空（admin 行禁用） */
function toggleRow(role: RoleItem, on: boolean) {
  if (isAdminRole(role)) return;
  for (const v of PERM_VERBS) setGrant(role.id, v, on);
}

/** 列级全选/清空 —— 把所有非 admin 行批量 toggle */
function toggleColumn(verb: PermVerb, on: boolean) {
  for (const r of allRoles.value) {
    if (isAdminRole(r)) continue;
    setGrant(r.id, verb, on);
  }
}

async function openPerms(row: ArchiveLibrary) {
  permsDialog.category = row;
  permsDialog.title = t('documents.authDialogTitle', { name: row.name });
  permsDialog.visible = true;
  permsDialog.loading = true;
  grants.value = new Map();
  initialGrants.value = new Map();
  try {
    const [roles, current] = await Promise.all([
      allRoles.value.length
        ? Promise.resolve(allRoles.value)
        : roleApi.list().then(rs => (allRoles.value = rs)),
      archiveApi.permissions(row.id),
    ]);
    // 把 {view,upload,update,delete} 拍扁成 Map<roleId, Set<verb>>
    const next = new Map<number, Set<PermVerb>>();
    for (const v of PERM_VERBS) {
      for (const rid of current[v] ?? []) {
        if (!next.has(rid)) next.set(rid, new Set<PermVerb>());
        next.get(rid)!.add(v);
      }
    }
    // admin 强制 4 权限勾选
    const adminRole = roles.find(isAdminRole);
    if (adminRole) {
      next.set(adminRole.id, new Set<PermVerb>([...PERM_VERBS]));
    }
    grants.value = next;
    initialGrants.value = new Map(
      Array.from(next.entries()).map(([k, v]) => [k, new Set(v)]),
    );
  } catch {
    ElMessage.error(t('documents.authLoadFailed'));
    permsDialog.visible = false;
  } finally {
    permsDialog.loading = false;
  }
}

/** 拍扁 grants 回后端期望的 {view: [roleIds], upload: [...], ...} */
function flattenGrants(): ArchivePermGrants {
  const out: ArchivePermGrants = { view: [], upload: [], update: [], delete: [] };
  for (const [roleId, verbs] of grants.value.entries()) {
    for (const v of PERM_VERBS) {
      if (verbs.has(v)) out[v].push(roleId);
    }
  }
  return out;
}

async function savePerms() {
  if (!permsDialog.category) return;
  permsDialog.saving = true;
  try {
    await archiveApi.updatePerms(permsDialog.category.id, flattenGrants());
    ElMessage.success(t('documents.authSaved'));
    permsDialog.visible = false;
    // 授权变更会影响 sidebar 显示 —— 立即刷新当前用户的菜单
    await auth.fetchMe();
    // 关键：当前用户自己（或 admin）的 perm 刚被改 → 重发 JWT 让浏览器侧立刻看到
    await auth.refreshTokens();
  } catch {
    /* http interceptor already toasted */
  } finally {
    permsDialog.saving = false;
  }
}

onMounted(reload);
</script>

<style scoped>
/* 页面布局 / 表格容器样式**全部继承 global.css**，本页不覆写
 * （与发票列表页 invoice/List.vue 保持一致，见 AGENTS §7.1 / §8.5.1）。
 *
 * 之前这里自定义过一套竖向撑满（min-height: calc(100vh - 64px) + flex:1 链路 +
 * el-table / inner-wrapper / body-wrapper 逐层 flex:1），结果与全局标准打架：
 * EP 的 .el-table 默认 height:fit-content，被 flex 拉高后表体在边框内留下
 * 一大片空白，竖向看起来"没撑满"。全局 .table-wrap 本来就按内容高度渲染，
 * 行数少时容器跟着矮 —— 这才是与发票列表一致的观感。 */

/* 名称主行：图标与文字并排，图标不参与截断 */
.archive-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.name-icon {
  color: var(--color-primary);
  font-size: 16px;
  flex-shrink: 0;
}
.strong {
  font-weight: 500;
  color: var(--color-text);
}
.mono {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--color-text-secondary);
}
.muted {
  color: var(--color-text-muted);
  font-size: 12px;
}
.count-cell {
  font-variant-numeric: tabular-nums;
  color: var(--color-text);
}
.status-pill {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 500;
}
.status-pill.on {
  background: color-mix(in srgb, var(--color-success) 14%, transparent);
  color: var(--color-success);
}
.status-pill.off {
  background: color-mix(in srgb, var(--color-text-muted) 14%, transparent);
  color: var(--color-text-muted);
}
.form-hint {
  margin-top: 4px;
  font-size: 12px;
}

/* ===================== 授权对话框 ===================== */
.auth-header {
  margin-bottom: 6px;
}
.auth-row {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.auth-role-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.auth-col-head {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  line-height: 1.2;
}
.auth-grid :deep(.el-table__cell) {
  padding: 6px 0 !important;
}
.auth-grid :deep(.cell) {
  padding: 0 8px !important;
}

/* 空状态：表格容器随行数自动调节（不强制 min-height） */
.cat-table-wrap .empty-state {
  min-height: 320px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 30px 12px;
  gap: 12px;
}
.empty-state .empty-icon {
  font-size: 56px;
  color: var(--color-primary-soft);
  background: var(--color-bg-elev);
  border: 1px solid var(--color-border-soft);
  border-radius: 50%;
  width: 96px;
  height: 96px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 8px;
}
.empty-state .empty-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text);
  margin: 0;
}
.empty-state .empty-desc {
  font-size: 13px;
  max-width: 460px;
  margin: 0 0 6px;
  line-height: 1.55;
}
</style>
