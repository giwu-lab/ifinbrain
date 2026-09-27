<template>
  <div class="page-container fade-in-up dp-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('design.pageTitle') }}</h1>
        <p class="page-desc muted">{{ t('design.pageDesc') }}</p>
      </div>
      <el-button
        v-if="auth.hasPerm('design:page:update')"
        type="primary"
        :icon="Plus"
        @click="openCreate"
      >
        {{ t('design.createPage') }}
      </el-button>
    </div>

    <div class="dp-table-wrap">
      <el-table
        :data="rows"
        v-loading="loading"
        border
        stripe
        size="small"
      >
        <el-table-column :label="t('design.colName')" min-width="180">
          <template #default="{ row }">
            <div class="dp-name-cell">
              <span class="dp-name">{{ row.name }}</span>
              <span class="muted small">· {{ row.code }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('design.colDescription')" min-width="200">
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('design.colStatus')" width="90">
          <template #default="{ row }">
            <span :class="['dp-status', row.status === 1 ? 'on' : 'off']">
              {{ row.status === 1 ? t('common.enabled') : t('common.disabled') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('design.colRoles')" min-width="160">
          <template #default="{ row }">
            <span v-if="(row as PortalPage).roleBindings.length === 0" class="muted small">{{ t('design.noBinding') }}</span>
            <span v-else class="dp-roles">
              <span v-for="r in (row as PortalPage).roleBindings" :key="r" class="dp-role-chip">{{ r }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('design.colUpdatedAt')" width="180">
          <template #default="{ row }">
            <span class="muted">{{ formatTime(row.updatedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="180" align="center" fixed="right">
          <template #default="{ row }">
            <el-tooltip :content="t('design.editDesign')" placement="top">
              <el-button size="small" link :icon="Edit" @click="onCmd('edit', row as PortalPage)" />
            </el-tooltip>
            <el-tooltip :content="t('design.preview')" placement="top">
              <el-button size="small" link :icon="View" @click="onCmd('preview', row as PortalPage)" />
            </el-tooltip>
            <el-dropdown trigger="click" @command="(c) => onCmd(c, row as PortalPage)">
              <el-button :icon="MoreFilled" size="small" link />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-if="auth.hasPerm('design:page:publish') && row.status !== 1" command="publish">
                    <span class="dp-text-success">
                      <el-icon><Promotion /></el-icon>{{ t('design.publish') }}
                    </span>
                  </el-dropdown-item>
                  <el-dropdown-item v-if="auth.hasPerm('design:page:publish') && row.status === 1" command="unpublish">
                    <span class="muted">
                      <el-icon><VideoPause /></el-icon>{{ t('design.unpublish') }}
                    </span>
                  </el-dropdown-item>
                  <el-dropdown-item command="duplicate" v-if="auth.hasPerm('design:page:update')">
                    <span><el-icon><CopyDocument /></el-icon>{{ t('design.duplicate') }}</span>
                  </el-dropdown-item>
                  <el-dropdown-item v-if="auth.hasPerm('design:page:delete')" command="delete" divided>
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
    </div>

    <div v-if="!loading && rows.length === 0" class="dp-empty">
      <el-icon class="empty-icon"><Files /></el-icon>
      <h3 class="empty-title">{{ t('design.emptyTitle') }}</h3>
      <p class="empty-desc">{{ t('design.emptyDesc') }}</p>
      <el-button v-if="auth.hasPerm('design:page:update')" type="primary" :icon="Plus" @click="openCreate">
        {{ t('design.emptyAction') }}
      </el-button>
    </div>

    <!-- 创建弹窗 -->
    <el-dialog v-model="createOpen" :title="t('design.createPage')" width="520px" align-center>
      <el-form :model="createForm" :rules="createRules" ref="createFormRef" label-width="100px">
        <el-form-item :label="t('design.colCode')" prop="code">
          <el-input v-model="createForm.code" :placeholder="t('design.phCode')" />
        </el-form-item>
        <el-form-item :label="t('design.colName')" prop="name">
          <el-input v-model="createForm.name" />
        </el-form-item>
        <el-form-item :label="t('design.colDescription')">
          <el-input v-model="createForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item :label="t('design.colRoles')">
          <el-checkbox-group v-model="createForm.roleBindings">
            <el-checkbox value="admin">admin</el-checkbox>
            <el-checkbox value="operator">operator</el-checkbox>
            <el-checkbox value="viewer">viewer</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="creating" @click="onCreate">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { useI18n } from 'vue-i18n';
import {
  Edit, Delete, MoreFilled, Plus, View, Promotion, VideoPause, CopyDocument, Files
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { portalPageApi, type PortalPage } from '@/api/portalPage';

const { t } = useI18n();
const auth = useAuthStore();
const router = useRouter();

const rows = ref<PortalPage[]>([]);
const loading = ref(false);

const createOpen = ref(false);
const creating = ref(false);
const createFormRef = ref<FormInstance>();
const createForm = reactive({
  code: '',
  name: '',
  description: '',
  roleBindings: [] as string[],
});
const createRules: FormRules = {
  code: [{ required: true, message: () => t('required') as string, trigger: 'blur' }],
  name: [{ required: true, message: () => t('required') as string, trigger: 'blur' }],
};

async function reload() {
  loading.value = true;
  try {
    rows.value = await portalPageApi.list();
  } finally {
    loading.value = false;
  }
}

function formatTime(s?: string | null) {
  if (!s) return '—';
  const d = new Date(s);
  if (isNaN(d.getTime())) return s;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function openCreate() {
  createForm.code = '';
  createForm.name = '';
  createForm.description = '';
  createForm.roleBindings = [];
  createOpen.value = true;
}

async function onCreate() {
  if (!createFormRef.value) return;
  await createFormRef.value.validate(async (valid) => {
    if (!valid) return;
    creating.value = true;
    try {
      const r = await portalPageApi.create({
        code: createForm.code.trim(),
        name: createForm.name.trim(),
        description: createForm.description || undefined,
        roleBindings: createForm.roleBindings,
        status: 0,
      });
      createOpen.value = false;
      ElMessage.success(t('common.created'));
      router.push(`/design/page/${r.id}/edit`);
    } catch (e: any) {
      ElMessage.error(e?.response?.data?.message || e?.message || String(e));
    } finally {
      creating.value = false;
    }
  });
}

async function onCmd(cmd: string, row: PortalPage) {
  if (cmd === 'edit') router.push(`/design/page/${row.id}/edit`);
  else if (cmd === 'preview') router.push(`/design/page/${row.id}/preview`);
  else if (cmd === 'publish') {
    try {
      await portalPageApi.publish(row.id);
      ElMessage.success(t('design.published'));
      await reload();
    } catch (e: any) {
      ElMessage.error(e?.response?.data?.message || String(e));
    }
  } else if (cmd === 'unpublish') {
    try {
      await portalPageApi.unpublish(row.id);
      ElMessage.success(t('design.unpublished'));
      await reload();
    } catch (e: any) {
      ElMessage.error(e?.response?.data?.message || String(e));
    }
  } else if (cmd === 'duplicate') {
    try {
      const dup = await portalPageApi.create({
        code: `${row.code}-copy-${Date.now().toString(36)}`,
        name: `${row.name} (copy)`,
        description: row.description || undefined,
        roleBindings: row.roleBindings,
        status: 0,
      });
      ElMessage.success(t('common.created'));
      router.push(`/design/page/${dup.id}/edit`);
    } catch (e: any) {
      ElMessage.error(e?.response?.data?.message || String(e));
    }
  } else if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(
        t('design.deleteConfirm', { name: row.name }),
        t('common.confirmDelete'),
        { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') }
      );
      await portalPageApi.remove(row.id);
      ElMessage.success(t('common.deleted'));
      await reload();
    } catch { /* user cancelled */ }
  }
}

onMounted(reload);
</script>

<style scoped>
.dp-page .page-header {
  display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 24px;
}
.dp-page .page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; }
.dp-page .page-desc  { margin: 4px 0 0; font-size: 13px; }

.dp-table-wrap {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-elev);
  overflow: hidden;
}
.dp-name-cell { display: inline-flex; gap: 8px; align-items: baseline; }
.dp-name { font-weight: 500; color: var(--color-text); }
.dp-status {
  font-size: 11px; padding: 1px 8px; border-radius: 2px;
  font-weight: 500;
}
.dp-status.on  { background: #e6f4ea; color: #0e8f5c; }
.dp-status.off { background: #f0f0eb; color: #8a8a85; }
.dp-roles { display: inline-flex; gap: 4px; flex-wrap: wrap; }
.dp-role-chip {
  font-family: ui-monospace, 'JetBrains Mono', monospace;
  font-size: 11px;
  padding: 1px 6px;
  background: var(--color-border-soft);
  border-radius: 2px;
  color: var(--color-text-secondary);
}
.dp-text-success { color: #0e8f5c; }

.dp-empty {
  display: flex; align-items: center; justify-content: center;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-sm);
  padding: 72px 24px;
  margin-top: 16px;
  background: var(--color-bg-elev);
  text-align: center;
}
.empty-icon { font-size: 40px; color: var(--color-text-muted); }
.empty-title { margin: 16px 0 4px; font-size: 16px; font-weight: 600; }
.empty-desc { margin: 0 0 20px; font-size: 13px; color: var(--color-text-secondary); }
</style>