<template>
  <div class="page-container fade-in-up form-page">
    <div class="table-wrap form-table-wrap">
      <div class="table-actions">
        <el-button
          v-if="auth.hasPerm('design:form:create')"
          type="primary"
          :icon="Plus"
          @click="goCreate"
        >{{ t('design.formCreate') }}</el-button>
      </div>
      <el-table
        :data="rows"
        v-loading="loading"
        border
        stripe
        size="small"
      >
        <el-table-column :label="t('design.formColCode')" prop="code" min-width="160">
          <template #default="{ row }">
            <code class="mono">{{ (row as FormDesignItem).code }}</code>
          </template>
        </el-table-column>
        <el-table-column :label="t('design.formColName')" prop="name" min-width="160" />
        <el-table-column :label="t('design.formColDescription')" prop="description" min-width="200" />
        <el-table-column :label="t('design.formColFields')" width="100" align="right">
          <template #default="{ row }">
            <span class="tabular">{{ (row as FormDesignItem).fields?.length || 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('design.formColStatus')" width="120" align="center">
          <template #default="{ row }">
            <span class="status" :class="(row as FormDesignItem).status === 1 ? 'on' : 'off'">
              {{ (row as FormDesignItem).status === 1 ? t('common.enabled') : t('common.disabled') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('design.formColUpdatedAt')" width="160">
          <template #default="{ row }">
            <span class="mono-time">{{ formatTime((row as FormDesignItem).updatedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" :width="WIDTH_ACTIONS" align="center" fixed="right">
          <template #default="{ row }">
            <el-tooltip v-if="auth.hasPerm('design:form:update')" :content="t('design.formEdit')" placement="top">
              <el-button size="small" link :icon="Edit" @click="goEdit(row as FormDesignItem)" />
            </el-tooltip>
            <el-tooltip v-if="auth.hasPerm('design:form:update')" :content="t('design.formPreview')" placement="top">
              <el-button size="small" link :icon="View" @click="goPreview(row as FormDesignItem)" />
            </el-tooltip>
            <el-tooltip v-if="auth.hasPerm('design:form:publish') && (row as FormDesignItem).status !== 1" :content="t('design.formPublish')" placement="top">
              <el-button size="small" link :icon="Promotion" @click="onPublish(row as FormDesignItem)" />
            </el-tooltip>
            <el-tooltip v-if="auth.hasPerm('design:form:publish') && (row as FormDesignItem).status === 1" :content="t('design.formUnpublish')" placement="top">
              <el-button size="small" link :icon="CircleClose" @click="onUnpublish(row as FormDesignItem)" />
            </el-tooltip>
            <el-dropdown v-if="auth.hasPerm('design:form:delete')" trigger="click" @command="(cmd) => onCmd(cmd, row as FormDesignItem)">
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
            <p class="muted">{{ t('design.formEmpty') }}</p>
            <el-button v-if="auth.hasPerm('design:form:create')" type="primary" :icon="Plus" @click="goCreate">
              {{ t('design.formCreate') }}
            </el-button>
          </div>
        </template>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useI18n } from 'vue-i18n';
import {
  Plus, Edit, View, Delete, MoreFilled, Files,
  Promotion, CircleClose
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { formDesignApi, type FormDesignItem } from '@/api/formDesign';
import { formatDateTime } from '@/utils/time';

const { t } = useI18n();
const router = useRouter();
const auth = useAuthStore();

const rows = ref<FormDesignItem[]>([]);
const loading = ref(false);

const WIDTH_ACTIONS = 192; // 4 icons + dropdown

function formatTime(s?: string | null) {
  return formatDateTime(s);
}

async function loadList() {
  loading.value = true;
  try {
    rows.value = await formDesignApi.list();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  } finally {
    loading.value = false;
  }
}

function goCreate() {
  router.push('/design/form/new');
}

function goEdit(row: FormDesignItem) {
  router.push(`/design/form/${row.id}/edit`);
}

function goPreview(row: FormDesignItem) {
  router.push(`/design/form/${row.id}/preview`);
}

async function onPublish(row: FormDesignItem) {
  try {
    await formDesignApi.publish(row.id);
    ElMessage.success(t('design.formPublished'));
    await loadList();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  }
}

async function onUnpublish(row: FormDesignItem) {
  try {
    await formDesignApi.unpublish(row.id);
    ElMessage.success(t('design.formUnpublished'));
    await loadList();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  }
}

async function onCmd(cmd: string, row: FormDesignItem) {
  if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(
        t('design.formDeleteConfirm', { name: row.name }),
        t('common.confirmDelete'),
        { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') }
      );
      await formDesignApi.remove(row.id);
      ElMessage.success(t('common.deleted'));
      await loadList();
    } catch { /* user cancelled */ }
  }
}

onMounted(loadList);
</script>

<style scoped>
.form-page .page-header {
  display: flex; justify-content: space-between; align-items: flex-end;
  position: sticky; top: 0; z-index: 30;
  background: var(--color-bg);
  padding: 8px 0; margin-bottom: 0;
}
.form-page .page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; line-height: 1.3; }
.form-page .page-desc  { margin: 4px 0 0; font-size: 13px; line-height: 1.4; }
.header-actions { display: flex; gap: 8px; }

/* 列表区：plain 1px 边框容器；避开 SaaS-card 套娃 */
.form-table-wrap {
  margin-top: 16px;
}

.status {
  font-size: 11px; padding: 1px 8px; border-radius: 2px;
  font-weight: 500;
}
.status.on  { background: var(--color-success-soft); color: var(--color-success); }
.status.off { background: var(--color-border-soft);   color: var(--color-text-muted); }

/* 空状态：紧凑卡片 */
.form-page .empty {
  display: flex; flex-direction: column; align-items: center;
  padding: 24px 10px; gap: 8px;
}
.form-page .empty-icon { font-size: 40px; color: var(--color-text-muted); }
</style>
