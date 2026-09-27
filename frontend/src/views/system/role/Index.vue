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
        <el-table-column :label="t('system.colPermissions')" min-width="320">
          <template #default="{ row }">
            <div class="perm-cluster">
              <div v-for="mod in groupedRolePerms(row as RoleItem)" :key="mod.name" class="perm-cluster-row">
                <span class="mod-label">{{ t(`system.permModule.${mod.name}`, mod.name) }}</span>
                <!-- 档案库模块特殊处理：按档案库细分组（财务部 / 销售部 ...），全局权限单独一行 -->
                <template v-if="mod.name === 'documents'">
                  <div v-if="globalDocsPerms(mod.perms).length" class="perm-cluster-sub">
                    <el-tag
                      v-for="p in globalDocsPerms(mod.perms)"
                      :key="p.code"
                      size="small"
                      effect="plain"
                      round
                      type="success"
                      class="perm-tag"
                    >
                      {{ actionOf(p.code) }}
                    </el-tag>
                  </div>
                  <div
                    v-for="cat in archivePermGroups(mod.perms)"
                    :key="cat.slug"
                    class="perm-cluster-sub"
                  >
                    <span class="archive-label">{{ cat.archiveName }}</span>
                    <el-tag
                      v-for="p in cat.perms"
                      :key="p.code"
                      size="small"
                      effect="plain"
                      round
                      class="perm-tag"
                    >
                      {{ actionOf(p.code) }}
                    </el-tag>
                  </div>
                </template>
                <!-- 其他模块：原有展示 -->
                <template v-else>
                  <el-tag
                    v-for="p in mod.perms.slice(0, 4)"
                    :key="p.code"
                    size="small"
                    effect="plain"
                    round
                    type="success"
                    class="perm-tag"
                  >
                    {{ actionOf(p.code) }}
                  </el-tag>
                  <el-tag
                    v-if="mod.perms.length > 4"
                    size="small"
                    effect="plain"
                    round
                    class="perm-tag"
                  >
                    +{{ mod.perms.length - 4 }}
                  </el-tag>
                </template>
              </div>
              <div v-if="(row as RoleItem).permissionCodes.length === 0" class="muted small">—</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.status')" width="100">
          <template #default="{ row }">
            <el-tag :type="(row as RoleItem).status === 1 ? 'success' : 'info'" size="small" effect="light">
              {{ (row as RoleItem).status === 1 ? t('common.enabled') : t('common.disabled') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="120" align="center" fixed="right">
          <template #default="{ row }">
            <!-- 编辑：图标直显 -->
            <el-tooltip v-if="auth.hasPerm('system:role:update')" :content="t('common.edit')" placement="top">
              <el-button size="small" link :icon="Edit" @click="openEdit(row as RoleItem)" />
            </el-tooltip>
            <!-- 删除：保留在 ··· 下拉 -->
            <el-dropdown v-if="auth.hasPerm('system:role:delete')" trigger="click" @command="(cmd: string) => onCmd(cmd, row as RoleItem)">
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
          <div class="perm-by-module">
            <div v-for="mod in groupedPermissions" :key="mod.name" class="perm-section">
              <div class="perm-section-title">
                <span class="perm-section-name">
                  {{ t(`system.permModule.${mod.name}`, mod.name) }}
                  <span class="muted small">({{ mod.perms.length }})</span>
                </span>
                <div class="section-actions">
                  <el-button link size="small" @click="toggleSection(mod, true)">
                    {{ t('system.permSelectAll') }}
                  </el-button>
                  <el-button link size="small" @click="toggleSection(mod, false)">
                    {{ t('system.permDeselectAll') }}
                  </el-button>
                </div>
              </div>
              <el-checkbox-group v-model="form.permissionIds" class="perm-grid">
                <el-checkbox v-for="p in mod.perms" :key="p.id" :value="p.id">
                  <span class="perm-code">{{ p.code }}</span>
                  <span class="perm-name muted small">{{ p.name }}</span>
                </el-checkbox>
              </el-checkbox-group>
            </div>
          </div>
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
import { Plus, Edit, Delete, MoreFilled } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { roleApi, type RoleItem, type PermissionItem } from '@/api/system';
import { groupByModule, actionOf, moduleOf } from '@/utils/permission';

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

/** 编辑对话框用：按 module 分组（用于 sectioned checkbox UI） */
const groupedPermissions = computed(() => groupByModule(allPermissions.value));

/** 按 module 全选 / 清空 */
function toggleSection(mod: { name: string; perms: PermissionItem[] }, on: boolean) {
  const ids = mod.perms.map(p => p.id);
  if (on) {
    form.permissionIds = Array.from(new Set([...form.permissionIds, ...ids]));
  } else {
    const set = new Set(ids);
    form.permissionIds = form.permissionIds.filter(id => !set.has(id));
  }
}

/** 角色列表展示用：把 code 列表 join 到 allPermissions 拿到 name + 按 module 分组 */
const permByCode = computed(() => {
  const m = new Map<string, PermissionItem>();
  for (const p of allPermissions.value) m.set(p.code, p);
  return m;
});

function groupedRolePerms(row: RoleItem): { name: string; perms: PermissionItem[] }[] {
  const codes = row.permissionCodes || [];
  const items: PermissionItem[] = [];
  for (const c of codes) {
    const found = permByCode.value.get(c);
    if (found) items.push(found);
  }
  return groupByModule(items);
}

/**
 * 档案库模块的全局权限（如 documents:view / documents:upload）—— 这些是 2 段式 code。
 * 由 ArchiveLibraryService 创建的 4 个档案库专属权限都是 3 段式 documents:{slug}:{verb}，归档案库分组。
 */
function globalDocsPerms(perms: PermissionItem[]): PermissionItem[] {
  return perms.filter(p => p.code.split(':').length === 2);
}

/**
 * 档案库权限分组：每个档案库一行，展示该角色在该档案库下的 4 个 verb。
 * 档案库显示名从 perm.name 字段提取（"财务部 档案库查看" → "财务部"）；fallback 到 slug。
 */
function archivePermGroups(perms: PermissionItem[]): { slug: string; archiveName: string; perms: PermissionItem[] }[] {
  const cats = new Map<string, { slug: string; archiveName: string; perms: PermissionItem[] }>();
  for (const p of perms) {
    const parts = p.code.split(':');
    if (parts.length !== 3 || parts[0] !== 'documents') continue;
    const slug = parts[1];
    if (!cats.has(slug)) {
      const name = (p.name || '').replace(/档案库(查看|上传|修改|删除)\s*$/, '').trim();
      cats.set(slug, { slug, archiveName: name || slug, perms: [] });
    }
    cats.get(slug)!.perms.push(p);
  }
  // 排序：按 slug 字典序稳定；如需业务排序可在 service 端做
  return Array.from(cats.values()).sort((a, b) => a.slug.localeCompare(b.slug));
}

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

/** ··· 下拉菜单统一入口 */
async function onCmd(cmd: string, row: RoleItem) {
  if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(
        t('system.confirmDeleteRole'),
        t('common.confirmDelete'),
        { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') }
      );
      await remove(row);
    } catch { /* 用户取消 */ }
  }
}

onMounted(async () => { await loadPerms(); await reload(); });
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 20px; }
.page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; }
.page-desc { margin: 4px 0 0; font-size: 13px; }

.perm-by-module {
  max-height: 360px;
  overflow-y: auto;
  border: 1px solid var(--line-soft, #ebeef5);
  border-radius: 4px;
  padding: 4px 0;
  background: var(--color-bg, #fafaf7);
}
.perm-section {
  border-bottom: 1px solid var(--line-soft, #ebeef5);
  padding: 8px 12px;
}
.perm-section:last-child { border-bottom: 0; }
.perm-section-title {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 6px;
  font-weight: 600; font-size: 13px;
  color: var(--color-text, #1a1a1a);
}
.perm-section-name { display: inline-flex; align-items: center; gap: 6px; }
.section-actions { display: inline-flex; gap: 4px; }

:deep(.perm-grid) {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 4px 12px;
  padding: 0;
}
:deep(.perm-grid .el-checkbox) { margin-right: 0; }
:deep(.perm-code) {
  font-family: 'SF Mono', Menlo, ui-monospace, monospace;
  font-size: 12px; color: var(--color-text, #1a1a1a);
  margin-right: 6px;
}
:deep(.perm-name) { font-size: 11px; }

.perm-cluster { display: flex; flex-direction: column; gap: 4px; line-height: 22px; }
.perm-cluster-row {
  display: flex; align-items: flex-start; gap: 6px; flex-wrap: wrap;
}
.perm-cluster-sub {
  display: flex; align-items: center; gap: 4px; flex-wrap: wrap;
  margin-left: 4px;
}
.archive-label {
  font-size: 11px; color: var(--color-text, #1a1a1a);
  background: var(--color-primary-soft, #e8edff);
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: 500;
  margin-right: 2px;
}
.mod-label {
  font-size: 11px; color: var(--color-text-secondary, #6b6b65);
  min-width: 56px; font-weight: 500;
  padding-top: 2px;
}
.perm-tag { font-family: 'SF Mono', Menlo, ui-monospace, monospace; }
</style>