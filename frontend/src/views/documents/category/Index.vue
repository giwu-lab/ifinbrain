<template>
  <div class="page-container fade-in-up cat-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('documents.categoryTitle') }}</h1>
        <p class="page-desc muted">{{ t('documents.categoryDesc') }}</p>
      </div>
      <el-button
        v-if="auth.hasPerm('documents:upload')"
        type="primary"
        :icon="Plus"
        @click="openCreate()"
      >
        {{ t('documents.createCategory') }}
      </el-button>
    </div>

    <!-- 列表区：plain 1px 边框容器，避开 SaaS-card 套娃 -->
    <div class="cat-table-wrap">
      <el-table
        v-if="rows.length > 0"
        :data="rows"
        v-loading="loading"
        border
        stripe
        size="small"
        row-key="id"
      >
        <el-table-column :label="t('documents.colCategoryName')" min-width="180">
          <template #default="{ row }">
            <div class="name-cell">
              <el-icon class="name-icon" v-if="(row as DocumentCategory).icon">
                <component :is="resolveIcon((row as DocumentCategory).icon)" />
              </el-icon>
              <span class="strong">{{ (row as DocumentCategory).name }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('documents.colCategorySlug')" prop="slug" width="200">
          <template #default="{ row }">
            <code class="mono">{{ (row as DocumentCategory).slug }}</code>
          </template>
        </el-table-column>
        <el-table-column :label="t('documents.colCategoryIcon')" prop="icon" width="100">
          <template #default="{ row }">
            <span class="muted">{{ (row as DocumentCategory).icon || 'Folder' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('documents.colCategorySort')" prop="sort" width="80" align="right" />
        <el-table-column :label="t('documents.colCategoryStatus')" width="90" align="center">
          <template #default="{ row }">
            <span class="status-pill" :class="(row as DocumentCategory).status === 1 ? 'on' : 'off'">
              {{ (row as DocumentCategory).status === 1 ? t('common.enabled') : t('common.disabled') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('documents.colCategoryCount')" width="100" align="right">
          <template #default="{ row }">
            <span class="count-cell">{{ ((row as DocumentCategory).fileCount ?? 0) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="120" align="center" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="auth.hasPerm('documents:update')"
              size="small"
              link
              type="primary"
              @click="openEdit(row as DocumentCategory)"
            >
              {{ t('common.edit') }}
            </el-button>
            <el-popconfirm
              v-if="auth.hasPerm('documents:delete')"
              :title="t('documents.deleteCategoryConfirm', { name: (row as DocumentCategory).name })"
              @confirm="onDelete(row as DocumentCategory)"
            >
              <template #reference>
                <el-button size="small" link type="danger">
                  {{ t('common.delete') }}
                </el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <!-- 空状态：无类目时居中展示，引导用户创建第一个 -->
      <div v-else-if="!loading" class="empty-state">
        <el-icon class="empty-icon"><Folder /></el-icon>
        <h2 class="empty-title">{{ t('documents.categoryEmptyTitle') }}</h2>
        <p class="empty-desc muted">{{ t('documents.categoryEmptyDesc') }}</p>
        <el-button
          v-if="auth.hasPerm('documents:upload')"
          type="primary"
          :icon="Plus"
          @click="openCreate()"
        >
          {{ t('documents.createCategory') }}
        </el-button>
      </div>
    </div>

    <!-- 新建 / 编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editing ? t('documents.editCategory') : t('documents.createCategory')"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" @submit.prevent>
        <el-form-item :label="t('documents.colCategoryName')" prop="name">
          <el-input v-model="form.name" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item :label="t('documents.colCategorySlug')" prop="slug">
          <el-input
            v-model="form.slug"
            :placeholder="t('documents.categorySlugHint')"
            maxlength="64"
          />
          <div class="form-hint muted">{{ t('documents.categorySlugHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('documents.colCategoryIcon')">
          <IconPicker v-model="form.icon" />
        </el-form-item>
        <el-form-item :label="t('documents.colCategorySort')">
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
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { Folder, Plus } from '@element-plus/icons-vue';
import * as ElIcons from '@element-plus/icons-vue';
import { useI18n } from 'vue-i18n';
import { useAuthStore } from '@/stores/auth';
import IconPicker from '@/components/IconPicker.vue';
import { categoryApi, type DocumentCategory } from '@/api/document';

const { t } = useI18n();
const auth = useAuthStore();

const loading = ref(false);
const rows = ref<DocumentCategory[]>([]);
const dialogVisible = ref(false);
const saving = ref(false);
const editing = ref<DocumentCategory | null>(null);
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
          cb(new Error(t('documents.categorySlugInvalid')));
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
    rows.value = await categoryApi.list();
  } catch {
    /* http interceptor already toasted */
  } finally {
    loading.value = false;
  }
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

function openEdit(row: DocumentCategory) {
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
      await categoryApi.update(editing.value.id, body);
      ElMessage.success(t('documents.updated'));
    } else {
      await categoryApi.create(body);
      ElMessage.success(t('documents.created'));
    }
    dialogVisible.value = false;
    await reload();
    // 同步刷新左侧目录树（sys_menu 也改了 / 增了 / 删了；auth.menus 重新拉一次）
    await auth.fetchMe();
  } catch {
    /* http interceptor */
  } finally {
    saving.value = false;
  }
}

async function onDelete(row: DocumentCategory) {
  try {
    await categoryApi.remove(row.id);
    ElMessage.success(t('documents.deleteSuccess'));
    await reload();
    await auth.fetchMe();
  } catch (e: any) {
    // 服务端已传 400 + 业务消息（"类目下仍有 N 个档案"），interceptor 已 toast
    // 但 BizException 400 仍会走到这里；前端无需再弹
    void e;
  }
}

onMounted(reload);
</script>

<style scoped>
/* ===== 页面布局（参照数据源页） ===== */
.cat-page {
  /* 页面至少填满 el-main（视口 - 64px 顶栏） */
  min-height: calc(100vh - 64px);
  display: flex;
  flex-direction: column;
}
.cat-page .page-header {
  display: flex; justify-content: space-between; align-items: center;
  /* 吸顶：标题 + 操作按钮始终可见 */
  position: sticky;
  top: 0;
  z-index: 30;
  background: var(--color-bg);
  padding: 8px 0;
  margin-bottom: 0;
  flex-shrink: 0;
}
.cat-page .page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; line-height: 1.3; }
.cat-page .page-desc  { margin: 4px 0 0; font-size: 13px; line-height: 1.4; }

/* ===== 列表区：plain 1px 边框容器（与数据源页对齐） ===== */
.cat-table-wrap {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-elev);
  overflow: visible;
  margin-top: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
}
.cat-table-wrap > .el-table,
.cat-table-wrap > .el-table > .el-table__inner-wrapper,
.cat-table-wrap > .el-table > .el-table__inner-wrapper > .el-table__body-wrapper {
  flex: 1;
}
/* 表头不吸顶 —— <el-table> 的内部布局对 position:sticky 极敏感，
 * 把 header-wrapper 设 sticky 会让 tbody 跑到表头上方（实测 bug）。
 * 退而求其次：只让页头吸顶；表头随页面滚动自然消失/出现。 */
:deep(.cat-table-wrap .el-table__header-wrapper) {
  background: var(--color-bg);
}
:deep(.cat-table-wrap .el-table__header-wrapper table) {
  background: var(--color-bg);
}

.name-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.name-icon {
  color: var(--color-primary);
  font-size: 16px;
}
.strong {
  font-weight: 500;
  color: var(--color-text);
}
.mono {
  font-family: 'SF Mono', Menlo, monospace;
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

/* 空状态：表格容器随行数自动调节（不强制 min-height） */
.cat-table-wrap .empty-state {
  min-height: 320px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 60px 24px;
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
  margin: 0 0 12px;
  line-height: 1.55;
}
</style>
