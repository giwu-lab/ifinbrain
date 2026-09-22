<template>
  <div class="page-container fade-in-up">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('system.roleTitle') }}</h1>
        <p class="page-desc muted">{{ t('system.roleTitleDesc') }}</p>
      </div>
      <el-button
        v-if="auth.hasPerm('system:role:create')"
        type="primary"
        :icon="Plus"
        @click="openCreate"
      >
        {{ t('system.newRole') }}
      </el-button>
    </div>

    <el-card class="card-table" shadow="never">
      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="id" :label="t('system.colId')" width="72" />
        <el-table-column :label="t('system.colCode')" prop="code" width="160">
          <template #default="{ row }">
            <el-tag type="info" effect="plain" size="small" round>{{ row.code }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('system.colName')" prop="name" min-width="140" />
        <el-table-column :label="t('system.colRemark')" prop="remark" min-width="200">
          <template #default="{ row }">
            <span v-if="row.remark" class="text-secondary">{{ row.remark }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('system.colPermissions')" min-width="240">
          <template #default="{ row }">
            <el-tag
              v-for="p in (row as RoleItem).permissionCodes.slice(0, 4)"
              :key="p"
              size="small"
              effect="plain"
              round
              type="success"
              style="margin-bottom: 4px;"
            >
              {{ p }}
            </el-tag>
            <el-tag
              v-if="(row as RoleItem).permissionCodes.length > 4"
              size="small"
              effect="plain"
              round
            >
              +{{ (row as RoleItem).permissionCodes.length - 4 }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.status')" width="100">
          <template #default="{ row }">
            <el-tag :type="(row as RoleItem).status === 1 ? 'success' : 'info'" size="small" effect="light">
              {{ (row as RoleItem).status === 1 ? t('common.enabled') : t('common.disabled') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="180" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="auth.hasPerm('system:role:update')"
              size="small"
              link
              type="primary"
              @click="openEdit(row as RoleItem)"
            >
              {{ t('common.edit') }}
            </el-button>
            <el-popconfirm
              v-if="auth.hasPerm('system:role:delete')"
              :title="t('system.confirmDeleteRole')"
              @confirm="remove(row as RoleItem)"
            >
              <template #reference>
                <el-button size="small" link type="danger">{{ t('common.delete') }}</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialog.visible"
      :title="dialog.title"
      width="640px"
      append-to-body
      destroy-on-close
      align-center
    >
      <el-form :model="form" :rules="rules" ref="formRef" label-width="100px">
        <el-form-item :label="t('system.colCode')" prop="code">
          <el-input v-model="form.code" :disabled="dialog.mode === 'edit'" />
        </el-form-item>
        <el-form-item :label="t('system.colName')" prop="name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item :label="t('system.colRemark')">
          <el-input v-model="form.remark" />
        </el-form-item>
        <el-form-item :label="t('common.status')">
          <el-switch
            v-model="form.status"
            :active-value="1"
            :inactive-value="0"
            :active-text="t('common.enabled')"
            :inactive-text="t('common.disabled')"
          />
        </el-form-item>
        <el-form-item :label="t('system.colPermissions')">
          <el-checkbox-group v-model="form.permissionIds" class="perm-grid">
            <el-checkbox v-for="p in allPermissions" :key="p.id" :value="p.id" :label="p.id">
              {{ p.code }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="dialog.loading" @click="save">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { roleApi, type RoleItem, type PermissionItem } from '@/api/system';

const { t } = useI18n();
const auth = useAuthStore();
const rows = ref<RoleItem[]>([]);
const allPermissions = ref<PermissionItem[]>([]);
const loading = ref(false);

const dialog = reactive({ visible: false, mode: 'create' as 'create' | 'edit', title: '', loading: false });
const form = reactive({
  id: 0, code: '', name: '', remark: '', status: 1 as number, permissionIds: [] as number[]
});
const formRef = ref<FormInstance>();
const rules = computed<FormRules>(() => ({
  code: [{ required: true, message: t('common.required') }],
  name: [{ required: true, message: t('common.required') }]
}));

async function reload() {
  loading.value = true;
  try { rows.value = await roleApi.list(); }
  finally { loading.value = false; }
}

async function loadPerms() { allPermissions.value = await roleApi.permissions(); }

function openCreate() {
  dialog.mode = 'create'; dialog.title = t('system.newRoleTitle');
  Object.assign(form, { id: 0, code: '', name: '', remark: '', status: 1, permissionIds: [] });
  dialog.visible = true;
}

function openEdit(row: RoleItem) {
  dialog.mode = 'edit'; dialog.title = `${t('system.editRoleTitle')}: ${row.name}`;
  Object.assign(form, { id: row.id, code: row.code, name: row.name, remark: row.remark || '', status: row.status, permissionIds: row.permissionIds });
  dialog.visible = true;
}

async function save() {
  if (!formRef.value) return;
  await formRef.value.validate(async (valid) => {
    if (!valid) return;
    dialog.loading = true;
    try {
      if (dialog.mode === 'create') {
        await roleApi.create({ ...form });
        ElMessage.success(t('common.created'));
      } else {
        await roleApi.update(form.id, { ...form });
        ElMessage.success(t('common.updated'));
      }
      dialog.visible = false;
      reload();
    } finally { dialog.loading = false; }
  });
}

async function remove(row: RoleItem) {
  await roleApi.remove(row.id);
  ElMessage.success(t('common.deleted'));
  reload();
}

onMounted(async () => { await loadPerms(); await reload(); });
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 20px; }
.page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; }
.page-desc { margin: 4px 0 0; font-size: 13px; }

:deep(.perm-grid) {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px 16px;
  max-height: 280px;
  overflow-y: auto;
  padding: 8px 0;
}
:deep(.perm-grid .el-checkbox) { margin-right: 0; }
</style>