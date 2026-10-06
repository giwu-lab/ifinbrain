<template>
  <div class="page-container fade-in-up">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('system.userTitle') }}</h1>
        <p class="page-desc muted">{{ t('system.userTitleDesc') }}</p>
      </div>
      <el-button
        v-if="auth.hasPerm('system:user:create')"
        type="primary"
        :icon="Plus"
        @click="openCreate"
      >
        {{ t('system.newUser') }}
      </el-button>
    </div>

    <el-card class="card-table" shadow="never">
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          :placeholder="t('system.userSearchPlaceholder')"
          style="width: 280px;"
          clearable
          @keyup.enter="reload"
          :prefix-icon="Search"
        />
        <el-button type="primary" @click="reload">{{ t('common.search') }}</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="id" :label="t('system.colId')" width="72" />
        <el-table-column :label="t('system.colUsername')" min-width="160">
          <template #default="{ row }">
            <div class="user-cell">
              <div class="mini-avatar">{{ (row.displayName || row.username).charAt(0).toUpperCase() }}</div>
              <div>
                <div class="strong">{{ row.displayName || row.username }}</div>
                <div class="muted small">{{ row.username }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('system.colEmail')" prop="email" min-width="180">
          <template #default="{ row }">
            <span v-if="row.email" class="link-text">{{ row.email }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('system.colRoles')" min-width="200">
          <template #default="{ row }">
            <el-tag v-for="r in (row as UserItem).roleNames" :key="r" size="small" effect="plain" round>
              {{ r }}
            </el-tag>
            <span v-if="!(row as UserItem).roleNames?.length" class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.status')" width="100" align="center">
          <template #default="{ row }">
            <el-tag
              :type="(row as UserItem).status === 1 ? 'success' : 'info'"
              effect="light"
              size="small"
            >
              {{ (row as UserItem).status === 1 ? t('common.enabled') : t('common.disabled') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('system.colLastLogin')" width="180" prop="lastLoginAt">
          <template #default="{ row }">
            <span v-if="(row as UserItem).lastLoginAt" class="mono-time">{{ (row as UserItem).lastLoginAt }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="120" align="center" fixed="right">
          <template #default="{ row }">
            <!-- 编辑：图标直显 -->
            <el-tooltip v-if="auth.hasPerm('system:user:update')" :content="t('common.edit')" placement="top">
              <el-button size="small" link :icon="Edit" @click="openEdit(row as UserItem)" />
            </el-tooltip>
            <!-- 删除：保留在 ··· 下拉。系统预设账号 admin 不提供删除入口（后端也会硬拦） -->
            <el-dropdown
              v-if="auth.hasPerm('system:user:delete') && !isPreset(row as UserItem)"
              trigger="click"
              @command="(cmd: string) => onCmd(cmd, row as UserItem)"
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
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10,20,50,100]"
        layout="total,sizes,prev,pager,next,jumper"
        @current-change="reload"
        @size-change="reload"
      />
    </el-card>

    <el-dialog
      v-model="dialog.visible"
      :title="dialog.title"
      width="560px"
      append-to-body
      destroy-on-close
      align-center
    >
      <el-form :model="form" :rules="rules" ref="formRef" label-width="100px">
        <el-form-item :label="t('system.colUsername')" prop="username">
          <el-input v-model="form.username" :disabled="dialog.mode === 'edit'" />
        </el-form-item>
        <el-form-item :label="t('system.passwordLabel')" :prop="dialog.mode === 'edit' ? '' : 'password'">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="dialog.mode === 'edit' ? t('system.passwordHint') : ''"
          />
        </el-form-item>
        <el-form-item :label="t('system.colDisplayName')">
          <el-input v-model="form.displayName" />
        </el-form-item>
        <el-form-item :label="t('system.colEmail')" prop="email">
          <el-input v-model="form.email" :placeholder="t('system.emailOptional')" clearable />
        </el-form-item>
        <el-form-item :label="t('system.colPhone')">
          <el-input v-model="form.phone" />
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
        <el-form-item :label="t('system.colRoles')">
          <el-select v-model="form.roleIds" multiple style="width: 100%;" :max-tag-count="3">
            <el-option v-for="r in allRoles" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
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
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { Plus, Search, Edit, Delete, MoreFilled } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { userApi, roleApi, type UserItem, type RoleItem } from '@/api/system';

const { t } = useI18n();
const auth = useAuthStore();
const rows = ref<UserItem[]>([]);
const total = ref(0);
const loading = ref(false);
const query = reactive({ page: 1, size: 10, keyword: '' });

const allRoles = ref<RoleItem[]>([]);

const dialog = reactive({ visible: false, mode: 'create' as 'create' | 'edit', title: '', loading: false });
const form = reactive({
  id: 0, username: '', password: '', displayName: '', email: '', phone: '',
  status: 1 as number, roleIds: [] as number[]
});
const formRef = ref<FormInstance>();
/** 邮箱选填：留空不校验，填了才要求是合法地址（与后端 @Email 同一口径） */
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const rules = computed<FormRules>(() => ({
  username: [{ required: true, message: t('common.required') }],
  password: [{ required: true, message: t('common.required') }],
  email: [
    {
      validator: (_r: unknown, v: string, cb: (e?: Error) => void) => {
        const s = (v ?? '').trim();
        if (!s || EMAIL_RE.test(s)) return cb();
        cb(new Error(t('system.invalidEmail')));
      },
      trigger: ['blur', 'change']
    }
  ]
}));

async function reload() {
  loading.value = true;
  try {
    const r = await userApi.list({ page: query.page, size: query.size, keyword: query.keyword });
    rows.value = r.items;
    total.value = r.total;
  } finally {
    loading.value = false;
  }
}

async function loadRoles() {
  allRoles.value = await roleApi.list();
}

function openCreate() {
  dialog.mode = 'create';
  dialog.title = t('system.newUserTitle');
  Object.assign(form, { id: 0, username: '', password: '', displayName: '', email: '', phone: '', status: 1, roleIds: [] });
  dialog.visible = true;
}

function openEdit(row: UserItem) {
  dialog.mode = 'edit';
  dialog.title = `${t('system.editUserTitle')}: ${row.username}`;
  Object.assign(form, {
    id: row.id, username: row.username, password: '',
    displayName: row.displayName || '', email: row.email || '', phone: row.phone || '',
    status: row.status, roleIds: row.roleIds
  });
  dialog.visible = true;
}

async function save() {
  if (!formRef.value) return;
  await formRef.value.validate(async (valid) => {
    if (!valid) return;
    dialog.loading = true;
    try {
      // 邮箱选填：去掉首尾空白后提交，空串由后端落成 NULL
      const email = form.email.trim();
      if (dialog.mode === 'create') {
        await userApi.create({ ...form, email });
        ElMessage.success(t('common.created'));
      } else {
        // 编辑时密码留空 = 不修改。直接把空串发出去会被后端长度校验拒掉，
        // 这里干脆不带上该字段（缺省语义就是「不动这个字段」）。
        const { password, ...rest } = form;
        await userApi.update(form.id, password ? { ...rest, email, password } : { ...rest, email });
        ElMessage.success(t('common.updated'));
      }
      dialog.visible = false;
      reload();
    } finally {
      dialog.loading = false;
    }
  });
}

/** 系统预设账号（与后端 SystemPresets.ADMIN_USERNAME 对应）不可删除 */
function isPreset(row: UserItem) {
  return row.username === 'admin';
}

async function remove(row: UserItem) {
  await userApi.remove(row.id);
  ElMessage.success(t('common.deleted'));
  reload();
}

/** ··· 下拉菜单统一入口 */
async function onCmd(cmd: string, row: UserItem) {
  if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(
        t('system.confirmDeleteUser'),
        t('common.confirmDelete'),
        { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') }
      );
      await remove(row);
    } catch { /* 用户取消 */ }
  }
}

onMounted(async () => {
  await loadRoles();
  await reload();
});
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 10px;
}
.page-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.01em;
}
.page-desc { margin: 4px 0 0; font-size: 13px; }

.user-cell { display: flex; align-items: center; gap: 10px; }
.mini-avatar {
  width: 32px; height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, #6366f1, #3b82f6);
  color: white;
  display: flex; align-items: center; justify-content: center;
  font-weight: 600;
  font-size: 13px;
  flex-shrink: 0;
}
.strong { font-weight: 600; color: var(--color-text); }
.small { font-size: 12px; }
.link-text { color: var(--color-primary); }
</style>