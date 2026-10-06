<template>
  <div class="page-container fade-in-up flow-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('design.flowTitle') }}</h1>
        <p class="page-desc muted">{{ t('design.flowDesc') }}</p>
      </div>
      <div class="header-actions">
        <el-button
          v-if="auth.hasPerm('design:flow:create')"
          type="primary"
          :icon="Plus"
          @click="goCreate"
        >{{ t('design.flowCreate') }}</el-button>
      </div>
    </div>

    <div class="flow-table-wrap">
      <el-table :data="rows" v-loading="loading" border stripe size="small">
        <el-table-column :label="t('design.flowColCode')" prop="code" min-width="160">
          <template #default="{ row }">
            <code class="mono">{{ (row as FlowDefineItem).code }}</code>
          </template>
        </el-table-column>
        <el-table-column :label="t('design.flowColName')" prop="name" min-width="160" />
        <el-table-column :label="t('design.flowColDescription')" prop="description" min-width="200" />
        <el-table-column :label="t('design.flowColNodes')" width="100" align="right">
          <template #default="{ row }">
            <span class="tabular">{{ (row as FlowDefineItem).nodeCount ?? 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('design.flowColEdges')" width="100" align="right">
          <template #default="{ row }">
            <span class="tabular">{{ (row as FlowDefineItem).edgeCount ?? 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('design.flowColVersion')" width="90" align="center">
          <template #default="{ row }">
            <span class="mono">v{{ (row as FlowDefineItem).version }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('design.flowColStatus')" width="100" align="center">
          <template #default="{ row }">
            <span class="status" :class="(row as FlowDefineItem).status === 1 ? 'on' : 'off'">
              {{ (row as FlowDefineItem).status === 1 ? t('common.enabled') : t('common.disabled') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('design.flowColUpdatedAt')" width="160">
          <template #default="{ row }">
            <span class="mono-time">{{ formatTime((row as FlowDefineItem).updatedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" :width="WIDTH_ACTIONS" align="center" fixed="right">
          <template #default="{ row }">
            <el-tooltip v-if="auth.hasPerm('design:flow:update')" :content="t('design.flowEdit')" placement="top">
              <el-button size="small" link :icon="Edit" @click="goEdit(row as FlowDefineItem)" />
            </el-tooltip>
            <el-tooltip v-if="auth.hasPerm('design:flow:update')" :content="t('design.flowPreview')" placement="top">
              <el-button size="small" link :icon="View" @click="goPreview(row as FlowDefineItem)" />
            </el-tooltip>
            <!-- 授权：图标直显（与档案库许可模式一致） -->
            <el-tooltip v-if="auth.hasPerm('design:flow:deploy')" :content="t('design.flowAuthBtn')" placement="top">
              <el-button size="small" link :icon="Key" @click="openPerms(row as FlowDefineItem)" />
            </el-tooltip>
            <el-tooltip v-if="auth.hasPerm('design:flow:deploy') && (row as FlowDefineItem).status !== 1" :content="t('design.flowPublish')" placement="top">
              <el-button size="small" link :icon="Promotion" @click="onPublish(row as FlowDefineItem)" />
            </el-tooltip>
            <el-tooltip v-if="auth.hasPerm('design:flow:deploy') && (row as FlowDefineItem).status === 1" :content="t('design.flowUnpublish')" placement="top">
              <el-button size="small" link :icon="CircleClose" @click="onUnpublish(row as FlowDefineItem)" />
            </el-tooltip>
            <el-dropdown v-if="auth.hasPerm('design:flow:delete')" trigger="click" @command="(cmd) => onCmd(cmd, row as FlowDefineItem)">
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
          <div class="empty">
            <el-icon class="empty-icon"><Files /></el-icon>
            <p class="muted">{{ t('design.flowEmpty') }}</p>
            <el-button v-if="auth.hasPerm('design:flow:create')" type="primary" :icon="Plus" @click="goCreate">
              {{ t('design.flowCreate') }}
            </el-button>
          </div>
        </template>
      </el-table>
    </div>

    <!--
      流程授权：行=角色，列=4 个 verb（view/update/deploy/delete）
      模式参照档案库授权：见 documents/archive/Index.vue 的 permsDialog 段
    -->
    <el-dialog
      v-model="permsDialog.visible"
      :title="permsDialog.title"
      width="640px"
      :close-on-click-modal="false"
      align-center
    >
      <div v-if="permsDialog.flow" class="auth-header">
        <div class="auth-row">
          <span class="muted small">{{ t('design.flowAuthCodeLabel') }}：</span>
          <code class="mono">{{ permsDialog.flow.code }}</code>
        </div>
        <div class="form-hint muted">{{ t('design.flowAuthRoleHint') }}</div>
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
          v-for="verb in FLOW_PERMS"
          :key="verb"
          :label="t(`design.flowAuth.perm.${verb}`)"
          align="center"
          width="120"
        >
          <template #header>
            <div class="auth-col-head">
              <span>{{ t(`design.flowAuth.perm.${verb}`) }}</span>
              <el-button link size="small" @click="toggleColumn(verb, true)">
                {{ t('design.flowAuthSelectAll') }}
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
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useI18n } from 'vue-i18n';
import {
  Plus, Edit, View, Delete, MoreFilled, Files, Key,
  Promotion, CircleClose
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { flowDefineApi, type FlowDefineItem } from '@/api/flowDefine';
import { roleApi, type RoleItem } from '@/api/system';
import { formatDateTime } from '@/utils/time';

const { t } = useI18n();
const router = useRouter();
const auth = useAuthStore();

const rows = ref<FlowDefineItem[]>([]);
const loading = ref(false);

/** 操作列宽度：4 icons + 1 dropdown = 224 */
const WIDTH_ACTIONS = 224;

/** 流程授权的 4 个 verb —— 与后端 FlowDefineService.FLOW_PERMS 对齐 */
const FLOW_PERMS = ['view', 'update', 'deploy', 'delete'] as const;
type FlowPermVerb = typeof FLOW_PERMS[number];

function formatTime(s?: string | null) { return formatDateTime(s); }

async function loadList() {
  loading.value = true;
  try {
    rows.value = await flowDefineApi.list();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  } finally {
    loading.value = false; }
}

function goCreate() { router.push('/design/flow/new'); }
function goEdit(row: FlowDefineItem) { router.push(`/design/flow/${row.id}/edit`); }
function goPreview(row: FlowDefineItem) { router.push(`/design/flow/${row.id}/preview`); }

async function onPublish(row: FlowDefineItem) {
  try {
    await flowDefineApi.publish(row.id);
    ElMessage.success(t('design.flowPublished'));
    await loadList();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  }
}
async function onUnpublish(row: FlowDefineItem) {
  try {
    await flowDefineApi.unpublish(row.id);
    ElMessage.success(t('design.flowUnpublished'));
    await loadList();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  }
}

async function onCmd(cmd: string, row: FlowDefineItem) {
  if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(
        t('design.flowDeleteConfirm', { name: row.name }),
        t('common.confirmDelete'),
        { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') }
      );
      await flowDefineApi.remove(row.id);
      ElMessage.success(t('common.deleted'));
      await loadList();
    } catch { /* cancelled */ }
  }
}

// ===================== 授权对话框 =====================

const allRoles = ref<RoleItem[]>([]);
/** 当前正在授权的流程 */
const permsDialog = reactive<{
  visible: boolean;
  loading: boolean;
  saving: boolean;
  title: string;
  flow: FlowDefineItem | null;
}>({
  visible: false,
  loading: false,
  saving: false,
  title: '',
  flow: null,
});
/** 当前流程的 4 个 verb 授权状态：Map<roleId, Set<verb>> —— 勾选/取消都改这里 */
const grants = ref<Map<number, Set<FlowPermVerb>>>(new Map());

function isAdminRole(role: RoleItem): boolean {
  return role.code === 'admin';
}

/** 一次性加载全局角色 —— 复用 4 次授权 dialog，省 round trip */
async function ensureRolesLoaded() {
  if (allRoles.value.length) return;
  allRoles.value = await roleApi.list();
}

async function openPerms(row: FlowDefineItem) {
  permsDialog.flow = row;
  permsDialog.title = t('design.flowAuthTitle', { name: row.name });
  permsDialog.visible = true;
  permsDialog.loading = true;
  grants.value = new Map();
  try {
    await ensureRolesLoaded();
    const data = await flowDefineApi.getPermissions(row.id);
    grants.value = new Map();
    for (const role of allRoles.value) {
      const set = new Set<FlowPermVerb>();
      for (const verb of FLOW_PERMS) {
        if ((data[verb] || []).includes(role.id)) set.add(verb);
      }
      grants.value.set(role.id, set);
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  } finally { permsDialog.loading = false; }
}

function hasGrant(roleId: number, verb: FlowPermVerb): boolean {
  return grants.value.get(roleId)?.has(verb) ?? false;
}

function setGrant(roleId: number, verb: FlowPermVerb, on: boolean) {
  const set = grants.value.get(roleId) ?? new Set<FlowPermVerb>();
  if (on) set.add(verb); else set.delete(verb);
  grants.value.set(roleId, set);
  // 触发响应式（Map 引用未变时要 force update）
  grants.value = new Map(grants.value);
}

function toggleCell(roleId: number, verb: FlowPermVerb, on: boolean | string | number) {
  setGrant(roleId, verb, Boolean(on));
}

function toggleColumn(verb: FlowPermVerb, on: boolean) {
  for (const r of allRoles.value) {
    if (isAdminRole(r)) continue;   // admin 永远保留所有 perm，UI 强制锁定
    setGrant(r.id, verb, on);
  }
}

async function savePerms() {
  if (!permsDialog.flow) return;
  // 拼成 Map<verb, roleId[]> —— 与后端期望一致
  const out: Record<string, number[]> = {};
  for (const verb of FLOW_PERMS) {
    out[verb] = [];
  }
  for (const [roleId, set] of grants.value) {
    for (const verb of set) {
      if (!out[verb].includes(roleId)) out[verb].push(roleId);
    }
  }
  permsDialog.saving = true;
  try {
    await flowDefineApi.updatePermissions(permsDialog.flow.id, out);
    ElMessage.success(t('common.saved'));
    permsDialog.visible = false;
    await loadList();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  } finally { permsDialog.saving = false; }
}

onMounted(loadList);
</script>

<style scoped>
.flow-page .page-header {
  display: flex; justify-content: space-between; align-items: flex-end;
  position: sticky; top: 0; z-index: 30;
  background: var(--color-bg);
  padding: 8px 0; margin-bottom: 0;
}
.flow-page .page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; line-height: 1.3; }
.flow-page .page-desc  { margin: 4px 0 0; font-size: 13px; line-height: 1.4; }
.header-actions { display: flex; gap: 8px; }

/* 列表区：plain 1px 边框容器；避开 SaaS-card 套娃 */
.flow-table-wrap {
  margin-top: 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-elev);
  overflow: visible;
}

.status { font-size: 11px; padding: 1px 8px; border-radius: 2px; font-weight: 500; }
.status.on  { background: var(--color-success-soft); color: var(--color-success); }
.status.off { background: var(--color-border-soft);   color: var(--color-text-muted); }

/* 空状态：紧凑卡片 */
.flow-page .empty {
  display: flex; flex-direction: column; align-items: center;
  padding: 24px 10px; gap: 8px;
}
.flow-page .empty-icon { font-size: 40px; color: var(--color-text-muted); }

/* 授权对话框 */
.auth-header {
  padding: 0 0 6px;
  border-bottom: 1px solid var(--color-border-soft);
  margin-bottom: 6px;
}
.auth-row {
  display: flex; align-items: center; gap: 6px;
  font-size: 13px;
}
.auth-role-cell {
  display: flex; align-items: center; gap: 8px;
}
.auth-col-head {
  display: flex; flex-direction: column; align-items: center; gap: 2px;
  font-size: 12px;
}
.form-hint {
  margin-top: 6px;
  font-size: 12px;
}
</style>