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
        ref="tableRef"
        :data="tree"
        v-loading="loading"
        row-key="id"
        :tree-props="{ children: 'children' }"
        :default-expand-all="false"
        border
        row-class-name="menu-row"
        @expand-change="handleExpand"
      >
        <el-table-column :label="t('system.colMenuName')" prop="name" min-width="180">
          <template #default="{ row }">
            <div class="name-cell">
              <!-- 自定义展开按钮（替代 EP 默认左侧的 indent + 箭头，省一列） -->
              <button
                v-if="(row as MenuItem).children?.length"
                class="tree-toggle"
                type="button"
                :aria-label="expandedIds.has(row.id) ? 'Collapse' : 'Expand'"
                @click.stop="toggleRow(row as MenuItem)"
              >
                <ArrowRight v-if="!expandedIds.has(row.id)" :size="14" class="tree-arrow" />
                <ArrowDown v-else :size="14" class="tree-arrow down" />
              </button>
              <span v-else class="tree-spacer" />
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
        <el-table-column :label="t('system.colPermission')" prop="permission" min-width="200">
          <template #default="{ row }">
            <template v-if="row.permission">
              <el-tag type="success" size="small" effect="plain" round>
                {{ permName(row.permission) || row.permission }}
              </el-tag>
              <code v-if="permName(row.permission)" class="perm-code muted">{{ row.permission }}</code>
            </template>
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
        <el-table-column :label="t('common.actions')" width="160" align="center" fixed="right">
          <template #default="{ row }">
            <!-- 新增子菜单：图标直显 -->
            <el-tooltip v-if="auth.hasPerm('system:menu:create')" :content="t('system.newMenu')" placement="top">
              <el-button size="small" link :icon="Plus" @click="openCreate((row as MenuItem).id)" />
            </el-tooltip>
            <!-- 编辑：图标直显 -->
            <el-tooltip v-if="auth.hasPerm('system:menu:update')" :content="t('common.edit')" placement="top">
              <el-button size="small" link :icon="Edit" @click="openEdit(row as MenuItem)" />
            </el-tooltip>
            <!-- 删除：保留在 ··· 下拉 -->
            <el-dropdown v-if="auth.hasPerm('system:menu:delete')" trigger="click" @command="(cmd: string) => onCmd(cmd, row as MenuItem)">
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
        <el-form-item :label="t('system.colMenuName')">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item :label="t('system.colType')">
          <el-radio-group v-model="form.type">
            <el-radio-button label="DIR">{{ t('system.typeDir') }}</el-radio-button>
            <el-radio-button label="MENU">{{ t('system.typeMenu') }}</el-radio-button>
            <el-radio-button label="BUTTON">{{ t('system.typeButton') }}</el-radio-button>
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
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus';
import { Plus, ArrowDown, ArrowRight, Edit, Delete, MoreFilled } from '@element-plus/icons-vue';
import * as ElIcons from '@element-plus/icons-vue';
import IconPicker from '@/components/IconPicker.vue';
import PermissionSelect from '@/components/PermissionSelect.vue';
import { useAuthStore } from '@/stores/auth';
import { menuApi, permissionApi, type MenuItem, type PermissionItem } from '@/api/system';

const { t } = useI18n();
const auth = useAuthStore();
const tree = ref<MenuItem[]>([]);
const loading = ref(false);
const tableRef = ref<any>(null);
/** 跟踪展开的 row id（EP 内部维护展开状态但不回写到 row 对象，需要前端记） */
const expandedIds = ref<Set<number>>(new Set());
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

/** 权限码 → 中文名映射（用于列表展示 code 旁的中文说明） */
const permByCode = ref<Map<string, string>>(new Map());
function permName(code: string): string {
  return permByCode.value.get(code) || '';
}

async function reload() {
  loading.value = true;
  try {
    const [t] = await Promise.all([
      menuApi.tree(),
      permissionApi.list().then(list => {
        const m = new Map<string, string>();
        for (const p of list as PermissionItem[]) m.set(p.code, p.name);
        permByCode.value = m;
      }).catch(() => { /* ignore — 列表展示降级 */ }),
    ]);
    tree.value = t;
  } finally { loading.value = false; }
}

/** 自定义展开按钮：直接同步更新 expandedIds + 调 EP toggle API 触发实际展开 */
function toggleRow(row: MenuItem) {
  const next = new Set(expandedIds.value);
  if (next.has(row.id)) next.delete(row.id);
  else next.add(row.id);
  expandedIds.value = next;
  tableRef.value?.toggleRowExpansion(row, undefined);
}

/** EP @expand-change 事件兜底（@row-click 等其他途径触发展开时同步状态） */
function handleExpand(...args: any[]) {
  const expandedRows = args[1] as Array<{ id: number }> | undefined;
  if (!Array.isArray(expandedRows)) return;
  expandedIds.value = new Set(expandedRows.map(r => r.id));
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

/** ··· 下拉菜单统一入口 */
async function onCmd(cmd: string, row: MenuItem) {
  if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(
        t('system.confirmDeleteMenu'),
        t('common.confirmDelete'),
        { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') }
      );
      await remove(row);
    } catch { /* 用户取消 */ }
  }
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

/* EP 树形表格默认行为：
 *   - 每个子行第一个 cell 内嵌 N 个 <span class="el-table__indent"> 占位（用于深度缩进）
 *   - 父行第一个 cell 留空给 .el-table__expand-icon 箭头
 * 我们用自定义 .tree-toggle 替代箭头，用 padding-left 替代 indent span，所以全部隐藏。 */
:deep(tr.el-table__row .el-table__placeholder),
:deep(tr.el-table__row .el-table__indent) { display: none; }
:deep(tr.el-table__row .el-table__expand-icon) { display: none; }

/* 缩进：用 row 级别 class 直接控制 cell padding-left，
 * 替代被隐藏的 indent span 的视觉位移。每级 +28px，根行 12px。*/
:deep(tr.el-table__row > td:first-child) { padding-left: 12px !important; }
:deep(tr.el-table__row--level-1 > td:first-child) { padding-left: 40px !important; }
:deep(tr.el-table__row--level-2 > td:first-child) { padding-left: 68px !important; }
:deep(tr.el-table__row--level-3 > td:first-child) { padding-left: 96px !important; }

/* 自定义展开按钮（贴 name cell 左边） */
.tree-toggle {
  display: inline-flex; align-items: center; justify-content: center;
  width: 20px; height: 20px;
  background: transparent; border: 0; cursor: pointer;
  border-radius: 4px;
  margin-right: 6px;
  color: var(--color-text-secondary, #6b6b65);
  transition: background-color .15s ease;
  padding: 0;
}
.tree-toggle:hover {
  background: var(--color-bg, #fafaf7);
  color: var(--color-primary, #2d5bff);
}
.tree-arrow {
  display: inline-block;
  vertical-align: middle;
  flex-shrink: 0;
}
.tree-spacer { display: inline-block; width: 20px; margin-right: 6px; }
.name-cell { display: inline-flex; align-items: center; gap: 4px; }
.name-icon {
  color: var(--color-primary, #2d5bff);
  font-size: 18px;
  margin-right: 4px;
}
/* EP 默认会把 indent span 隐藏（level 0 时），所以根行自然靠左。 */

/* 层级引导线：在第一列 cell 左侧加 2px 软色竖线 */
:deep(tr.el-table__row--level-1 > td:first-child) {
  border-left: 2px solid var(--color-border-soft);
}
:deep(tr.el-table__row--level-2 > td:first-child) {
  border-left: 2px solid var(--color-primary-soft);
}
:deep(tr.el-table__row--level-3 > td:first-child) {
  border-left: 2px solid var(--color-primary);
}
.perm-code {
  font-family: 'SF Mono', Menlo, ui-monospace, monospace;
  font-size: 11px;
  margin-left: 8px;
  padding: 1px 6px;
  background: var(--color-bg, #fafaf7);
  border: 1px solid var(--line-soft, #ebeef5);
  border-radius: 3px;
}

/* 紧凑表格：缩窄行高 / cell padding */
:deep(.menu-row.el-table__row td) { padding: 6px 0 !important; }
:deep(.menu-row.el-table__row .cell) { padding: 0 12px !important; }
:deep(.el-table.menu-row th.el-table__cell) { padding: 8px 0 !important; }
</style>