<template>
  <div class="page-container fade-in-up">
    <div class="table-wrap">
      <div class="table-actions">
        <el-input
          v-model="keyword"
          :placeholder="t('system.deptSearchPlaceholder')"
          size="default"
          clearable
          style="width: 220px;"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button
          v-if="auth.hasPerm('system:dept:create')"
          type="primary"
          :icon="Plus"
          @click="openCreate(0)"
        >
          {{ t('system.newDept') }}
        </el-button>
      </div>

      <el-table
        ref="tableRef"
        :data="displayTree"
        v-loading="loading"
        row-key="id"
        :tree-props="{ children: 'children' }"
        :default-expand-all="false"
        border
        row-class-name="dept-row"
        empty-text=""
        @expand-change="handleExpand"
      >
        <el-table-column :label="t('system.colDeptName')" prop="name" min-width="220">
          <template #default="{ row }">
            <div class="name-cell">
              <button
                v-if="(row as DeptItem).children?.length"
                class="tree-toggle"
                type="button"
                :aria-label="expandedIds.has(row.id) ? 'Collapse' : 'Expand'"
                @click.stop="toggleRow(row as DeptItem)"
              >
                <ArrowRight v-if="!expandedIds.has(row.id)" :size="14" class="tree-arrow" />
                <ArrowDown v-else :size="14" class="tree-arrow down" />
              </button>
              <span v-else class="tree-spacer" />
              <el-icon class="name-icon"><OfficeBuilding /></el-icon>
              <span class="strong">{{ row.name }}</span>
              <el-tag v-if="row.status !== 1" size="small" type="info" effect="plain" round>
                {{ t('common.disabled') }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('system.colDeptCode')" prop="code" width="150">
          <template #default="{ row }">
            <code v-if="row.code" class="code-chip">{{ row.code }}</code>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('system.colLeader')" prop="leader" width="140">
          <template #default="{ row }">
            <span v-if="row.leader">{{ row.leader }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('system.colPhone')" prop="phone" width="150">
          <template #default="{ row }">
            <span v-if="row.phone">{{ row.phone }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('system.colSort')" prop="sort" width="80" align="center" />
        <el-table-column :label="t('common.status')" width="100" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="(row as DeptItem).status === 1"
              :disabled="!auth.hasPerm('system:dept:update')"
              size="small"
              @change="(v: any) => onToggleStatus(row as DeptItem, v)"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="160" align="center" fixed="right">
          <template #default="{ row }">
            <el-tooltip
              v-if="auth.hasPerm('system:dept:create')"
              :content="t('system.newSubDept')"
              placement="top"
            >
              <el-button size="small" link :icon="Plus" @click="openCreate((row as DeptItem).id)" />
            </el-tooltip>
            <el-tooltip
              v-if="auth.hasPerm('system:dept:update')"
              :content="t('common.edit')"
              placement="top"
            >
              <el-button size="small" link :icon="Edit" @click="openEdit(row as DeptItem)" />
            </el-tooltip>
            <el-dropdown
              v-if="auth.hasPerm('system:dept:delete')"
              trigger="click"
              @command="(cmd: string) => onCmd(cmd, row as DeptItem)"
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
      <p v-if="!loading && displayTree.length === 0" class="empty-hint muted">
        {{ keyword.trim() ? t('system.deptSearchEmpty') : t('system.deptTitleDesc') }}
      </p>
    </div>

    <el-dialog
      v-model="dialog.visible"
      :title="dialog.title"
      width="600px"
      append-to-body
      destroy-on-close
      align-center
    >
      <el-form :model="form" ref="formRef" :rules="rules" label-width="100px">
        <el-form-item :label="t('system.colParentDept')">
          <el-tree-select
            v-model="form.parentId"
            :data="parentOpts"
            :props="treeProps"
            check-strictly
            clearable
            style="width: 100%;"
          />
        </el-form-item>
        <el-form-item :label="t('system.colDeptName')" prop="name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item :label="t('system.colDeptCode')">
          <el-input v-model="form.code" :placeholder="t('system.deptCodeHint')" />
        </el-form-item>
        <el-form-item :label="t('system.colLeader')">
          <el-input v-model="form.leader" />
        </el-form-item>
        <el-form-item :label="t('system.colPhone')">
          <el-input v-model="form.phone" />
        </el-form-item>
        <el-form-item :label="t('system.colSort')">
          <el-input-number v-model="form.sort" :min="0" :max="9999" />
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
        <el-form-item :label="t('system.colDeptRemark')">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="dialog.loading" @click="save">
          {{ t('common.save') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed, watch, nextTick } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus';
import { Plus, ArrowDown, ArrowRight, Edit, Delete, MoreFilled, OfficeBuilding, Search } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { invalidateDeptOptions } from '@/composables/useOptionsCache';
import { deptApi, type DeptItem, type DeptPayload } from '@/api/system';

const { t } = useI18n();
const auth = useAuthStore();

const tree = ref<DeptItem[]>([]);
const loading = ref(false);
const keyword = ref('');
const tableRef = ref<any>(null);
const expandedIds = ref<Set<number>>(new Set());

const treeProps = { value: 'id' as const, label: 'name' as const, children: 'children' as const };

const dialog = reactive({
  visible: false,
  mode: 'create' as 'create' | 'edit',
  title: '',
  loading: false,
});

const form = reactive<DeptPayload & { id: number }>({
  id: 0,
  parentId: 0,
  name: '',
  code: '',
  leader: '',
  phone: '',
  sort: 0,
  status: 1,
  remark: '',
});

const formRef = ref<FormInstance>();
const rules = computed(() => ({
  name: [{ required: true, message: t('system.deptNameRequired'), trigger: 'blur' }],
}));

/**
 * 按关键词过滤树：命中节点保留；未命中但有命中后代的节点也保留（否则匹配项会被祖先藏起来）。
 */
function filterTree(nodes: DeptItem[], kw: string): DeptItem[] {
  const out: DeptItem[] = [];
  for (const n of nodes) {
    const kids = n.children?.length ? filterTree(n.children, kw) : [];
    const hit =
      n.name.toLowerCase().includes(kw) || (n.code || '').toLowerCase().includes(kw);
    if (hit || kids.length) {
      const copy: DeptItem = { ...n };
      if (kids.length) copy.children = kids;
      else delete copy.children;
      out.push(copy);
    }
  }
  return out;
}

const displayTree = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  return kw ? filterTree(tree.value, kw) : tree.value;
});

/**
 * 上级部门候选项。编辑时剔除自己整棵子树 —— 让后端「不能移动到自己的下级」
 * 的 400 校验在前端就选不出来。
 */
const parentOpts = computed(() => {
  const excluded = dialog.mode === 'edit' ? form.id : 0;
  const prune = (nodes: DeptItem[]): DeptItem[] =>
    nodes
      .filter((n) => n.id !== excluded)
      .map((n) => {
        const copy: DeptItem = { ...n };
        if (n.children?.length) copy.children = prune(n.children);
        return copy;
      });
  return [{ id: 0, name: t('system.parentRoot'), children: prune(tree.value) }];
});

async function reload() {
  loading.value = true;
  try {
    tree.value = await deptApi.tree();
  } finally {
    loading.value = false;
  }
  // 部门增删改后清掉表单「部门」字段的模块级选项缓存 —— 否则本会话内
  // 后续打开的表单仍会看到旧的部门树。
  invalidateDeptOptions();
}

/** 数据或过滤条件变化后，展开所有含子节点的行（收起状态交给用户自己点） */
watch(
  displayTree,
  (rows) => {
    const parents = rows.filter((r) => r.children?.length);
    expandedIds.value = new Set(parents.map((r) => r.id));
    nextTick(() => {
      for (const r of parents) tableRef.value?.toggleRowExpansion(r, true);
    });
  },
  { flush: 'post' }
);

function toggleRow(row: DeptItem) {
  const next = new Set(expandedIds.value);
  if (next.has(row.id)) next.delete(row.id);
  else next.add(row.id);
  expandedIds.value = next;
  tableRef.value?.toggleRowExpansion(row, undefined);
}

/** EP @expand-change 兜底：其他途径触发展开时同步按钮状态 */
function handleExpand(...args: any[]) {
  const expandedRows = args[1] as Array<{ id: number }> | undefined;
  if (!Array.isArray(expandedRows)) return;
  expandedIds.value = new Set(expandedRows.map((r) => r.id));
}

function openCreate(parentId = 0) {
  dialog.mode = 'create';
  dialog.title = parentId ? t('system.newSubDept') : t('system.newDeptTitle');
  Object.assign(form, {
    id: 0,
    parentId,
    name: '',
    code: '',
    leader: '',
    phone: '',
    sort: 0,
    status: 1,
    remark: '',
  });
  dialog.visible = true;
}

function openEdit(row: DeptItem) {
  dialog.mode = 'edit';
  dialog.title = `${t('system.editDeptTitle')}: ${row.name}`;
  Object.assign(form, {
    id: row.id,
    parentId: row.parentId,
    name: row.name,
    code: row.code || '',
    leader: row.leader || '',
    phone: row.phone || '',
    sort: row.sort,
    status: row.status,
    remark: row.remark || '',
  });
  dialog.visible = true;
}

async function save() {
  const ok = await formRef.value?.validate().catch(() => false);
  if (!ok) return;
  dialog.loading = true;
  try {
    const payload: DeptPayload = {
      name: form.name,
      parentId: form.parentId || 0,
      code: form.code,
      leader: form.leader,
      phone: form.phone,
      sort: form.sort,
      status: form.status,
      remark: form.remark,
    };
    if (dialog.mode === 'create') await deptApi.create(payload);
    else await deptApi.update(form.id, payload);
    ElMessage.success(t('common.saved'));
    dialog.visible = false;
    reload();
  } finally {
    dialog.loading = false;
  }
}

async function onToggleStatus(row: DeptItem, checked: boolean) {
  await deptApi.update(row.id, { status: checked ? 1 : 0 });
  ElMessage.success(t('common.saved'));
  reload();
}

async function onCmd(cmd: string, row: DeptItem) {
  if (cmd !== 'delete') return;
  try {
    await ElMessageBox.confirm(
      t('system.confirmDeleteDept'),
      t('common.confirmDelete'),
      {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel'),
      }
    );
  } catch {
    return; // 用户取消
  }
  await deptApi.remove(row.id);
  ElMessage.success(t('common.deleted'));
  reload();
}

onMounted(reload);
</script>

<style scoped>
.header-actions { display: flex; align-items: center; gap: 10px; }
.empty-hint { text-align: center; font-size: 13px; padding: 18px 0 6px; }
.name-cell { display: inline-flex; align-items: center; gap: 4px; }
.name-icon { color: var(--color-primary); font-size: 16px; margin-right: 4px; }
.strong { font-weight: 600; color: var(--color-text); }
.code-chip {
  font-family: 'SF Mono', Menlo, ui-monospace, monospace;
  font-size: 12px;
  padding: 1px 6px;
  background: var(--color-bg);
  border: 1px solid var(--line-soft, #ebeef5);
  border-radius: 3px;
  color: var(--color-text-secondary);
}
.danger-text { color: var(--color-danger, #f56c6c); display: inline-flex; align-items: center; gap: 4px; }

/* 与菜单页一致：用自定义 toggle 替代 EP 内置箭头 */
:deep(tr.el-table__row .el-table__placeholder),
:deep(tr.el-table__row .el-table__indent) { display: none; }
:deep(tr.el-table__row .el-table__expand-icon) { display: none; }

:deep(tr.el-table__row > td:first-child) { padding-left: 12px !important; }
:deep(tr.el-table__row--level-1 > td:first-child) { padding-left: 40px !important; }
:deep(tr.el-table__row--level-2 > td:first-child) { padding-left: 68px !important; }
:deep(tr.el-table__row--level-3 > td:first-child) { padding-left: 96px !important; }

.tree-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  background: transparent;
  border: 0;
  cursor: pointer;
  border-radius: 4px;
  margin-right: 6px;
  color: var(--color-text-secondary, #6b6b65);
  transition: background-color 0.15s ease;
  padding: 0;
}
.tree-toggle:hover {
  background: var(--color-bg, #fafaf7);
  color: var(--color-primary, #2d5bff);
}
.tree-arrow { display: inline-block; vertical-align: middle; flex-shrink: 0; }
.tree-spacer { display: inline-block; width: 20px; margin-right: 6px; }

:deep(tr.el-table__row--level-1 > td:first-child) { border-left: 2px solid var(--color-border-soft); }
:deep(tr.el-table__row--level-2 > td:first-child) { border-left: 2px solid var(--color-primary-soft); }
:deep(tr.el-table__row--level-3 > td:first-child) { border-left: 2px solid var(--color-primary); }

:deep(.dept-row.el-table__row td) { padding: 6px 0 !important; }
:deep(.dept-row.el-table__row .cell) { padding: 0 12px !important; }
:deep(.el-table .dept-row th.el-table__cell) { padding: 8px 0 !important; }
</style>
