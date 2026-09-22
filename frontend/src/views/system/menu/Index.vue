<template>
  <div class="page-container fade-in-up">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('system.menuTitle') }}</h1>
        <p class="page-desc muted">{{ t('system.menuTitleDesc') }}</p>
      </div>
      <el-button
        v-if="auth.hasPerm('system:menu:create')"
        type="primary"
        :icon="Plus"
        @click="openCreate()"
      >
        {{ t('system.newMenu') }}
      </el-button>
    </div>

    <el-card class="card-table" shadow="never">
      <el-table
        :data="tree"
        v-loading="loading"
        row-key="id"
        :tree-props="{ children: 'children' }"
        :indent-size="24"
        border
        default-expand-all
        row-class-name="menu-row"
      >
        <el-table-column :label="t('system.colUsername').replace('名','称')" prop="name" min-width="180">
          <template #default="{ row }">
            <div class="name-cell">
              <el-icon v-if="(row as MenuItem).icon" class="name-icon">
                <component :is="iconOf((row as MenuItem).icon)" />
              </el-icon>
              <span class="strong">{{ row.name }}</span>
              <el-tag
                v-if="(row as MenuItem).type !== 'MENU'"
                size="small"
                effect="plain"
                :type="(row as MenuItem).type === 'DIR' ? 'warning' : 'info'"
                round
              >
                {{ (row as MenuItem).type === 'DIR' ? t('system.typeDir') : t('system.typeButton') }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.path')" prop="path" width="200">
          <template #default="{ row }">
            <code class="path-code">{{ row.path }}</code>
          </template>
        </el-table-column>
        <el-table-column :label="t('system.colComponent')" prop="component" width="220">
          <template #default="{ row }">
            <span v-if="row.component" class="muted">{{ row.component }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('system.colSort')" prop="sort" width="80" />
        <el-table-column :label="t('system.colPermission')" prop="permission" width="180">
          <template #default="{ row }">
            <el-tag v-if="row.permission" type="success" size="small" effect="plain" round>{{ row.permission }}</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.status')" width="100">
          <template #default="{ row }">
            <el-switch
              :model-value="(row as MenuItem).status === 1"
              disabled
              size="small"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="240" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="auth.hasPerm('system:menu:create')"
              size="small"
              link
              type="primary"
              @click="openCreate((row as MenuItem).id)"
            >
              + {{ t('system.newMenu') }}
            </el-button>
            <el-button
              v-if="auth.hasPerm('system:menu:update')"
              size="small"
              link
              type="primary"
              @click="openEdit(row as MenuItem)"
            >
              {{ t('common.edit') }}
            </el-button>
            <el-popconfirm
              v-if="auth.hasPerm('system:menu:delete')"
              :title="t('system.confirmDeleteMenu')"
              @confirm="remove(row as MenuItem)"
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
      width="600px"
      append-to-body
      destroy-on-close
      align-center
    >
      <el-form :model="form" ref="formRef" label-width="100px">
        <el-form-item :label="t('system.colParent')">
          <el-tree-select
            v-model="form.parentId"
            :data="parentOpts"
            :props="treeProps"
            check-strictly
            clearable
            style="width: 100%;"
          />
        </el-form-item>
        <el-form-item :label="t('system.colUsername').replace('名','称')">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item :label="t('system.colType')">
          <el-radio-group v-model="form.type">
            <el-radio-button label="DIR" />
            <el-radio-button label="MENU" />
            <el-radio-button label="BUTTON" />
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('common.path')">
          <el-input v-model="form.path" placeholder="/example" />
        </el-form-item>
        <el-form-item v-if="form.type === 'MENU'" :label="t('system.colComponent')">
          <el-select
            v-model="form.component"
            filterable
            allow-create
            style="width: 100%;"
          >
            <el-option v-for="k in knownComponents" :key="k" :label="k" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('system.colIcon')">
          <IconPicker v-model="form.icon" />
        </el-form-item>
        <el-form-item :label="t('system.colSort')">
          <el-input-number v-model="form.sort" :min="0" :max="9999" />
        </el-form-item>
        <el-form-item :label="t('system.colPermission')">
          <PermissionSelect v-model="form.permission" />
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
import { ElMessage, type FormInstance } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import * as ElIcons from '@element-plus/icons-vue';
import IconPicker from '@/components/IconPicker.vue';
import PermissionSelect from '@/components/PermissionSelect.vue';
import { useAuthStore } from '@/stores/auth';
import { menuApi, type MenuItem } from '@/api/system';

const { t } = useI18n();
const auth = useAuthStore();
const tree = ref<MenuItem[]>([]);
const loading = ref(false);
const knownComponents = [
  'dashboard/Index','invoice/Upload','invoice/List',
  'system/user/Index','system/role/Index','system/menu/Index','system/storage/Index'
];

const dialog = reactive({ visible: false, mode: 'create' as 'create' | 'edit', title: '', loading: false });
const form = reactive<Partial<MenuItem>>({ id: 0, parentId: 0, name: '', type: 'MENU', path: '', component: '', icon: '', sort: 0, permission: '', status: 1 });
const formRef = ref<FormInstance>();

const parentOpts = computed(() => [{ id: 0, name: t('system.parentRoot'), children: tree.value }]);
const treeProps = { value: 'id' as const, label: 'name' as const, children: 'children' as const };

function iconOf(name?: string) {
  if (!name) return ElIcons.Document;
  return (ElIcons as Record<string, unknown>)[name] as any || ElIcons.Document;
}

async function reload() {
  loading.value = true;
  try { tree.value = await menuApi.tree(); }
  finally { loading.value = false; }
}

function openCreate(parentId = 0) {
  dialog.mode = 'create'; dialog.title = t('system.newMenuTitle');
  Object.assign(form, { id: 0, parentId, name: '', type: 'MENU', path: '', component: '', icon: '', sort: 0, permission: '', status: 1 });
  dialog.visible = true;
}

function openEdit(row: MenuItem) {
  dialog.mode = 'edit'; dialog.title = `${t('system.editMenuTitle')}: ${row.name}`;
  Object.assign(form, {
    id: row.id, parentId: row.parentId, name: row.name, type: row.type,
    path: row.path, component: row.component, icon: row.icon,
    sort: row.sort, permission: row.permission, status: row.status
  });
  dialog.visible = true;
}

async function save() {
  dialog.loading = true;
  try {
    if (dialog.mode === 'create') await menuApi.create(form);
    else await menuApi.update(form.id!, form);
    ElMessage.success(t('common.saved'));
    dialog.visible = false;
    reload();
  } finally { dialog.loading = false; }
}

async function remove(row: MenuItem) {
  await menuApi.remove(row.id);
  ElMessage.success(t('common.deleted'));
  reload();
}

onMounted(reload);
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 20px; }
.page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; }
.page-desc { margin: 4px 0 0; font-size: 13px; }
.name-cell { display: flex; align-items: center; gap: 8px; }
.name-icon { color: var(--color-primary); font-size: 16px; }
.strong { font-weight: 600; color: var(--color-text); }
.path-code {
  background: var(--color-bg);
  padding: 2px 8px;
  border-radius: 4px;
  font-family: 'SF Mono', Menlo, monospace;
  font-size: 12px;
  color: var(--color-text-secondary);
}

/* 层级引导线：让多层子菜单的缩进更易识别 */
:deep(.el-table__indent) {
  position: relative;
}
:deep(.el-table__indent)::before {
  content: '';
  position: absolute;
  left: 50%;
  top: 0;
  bottom: 0;
  width: 2px;
  margin-left: -1px;
  background: var(--color-border-soft);
  border-radius: 1px;
  opacity: 0.7;
}
:deep(tr.is-leaf .el-table__indent)::before {
  display: none;
}
</style>