<template>
  <div class="page-container fade-in-up ds-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('dataMgmt.dsTitle') }}</h1>
        <p class="page-desc muted">{{ t('dataMgmt.dsDesc') }}</p>
      </div>
      <el-button
        v-if="auth.hasPerm('data:datasource:update')"
        type="primary"
        :icon="Plus"
        @click="openCreate"
      >
        {{ t('dataMgmt.colName') }}
      </el-button>
    </div>

    <!--
      主列表区：plain 1px 边框容器，避开 .card-table 套娃。
      头部留 12px 边距给表头；行内不重复 padding。
    -->
    <div class="ds-table-wrap">
      <el-table
        :data="rows"
        v-loading="loading"
        border
        stripe
        class="ds-table"
        empty-text="—"
      >
        <el-table-column :label="t('dataMgmt.colName')" prop="name" min-width="90">
          <template #default="{ row }">
            <span class="ds-name">{{ row.name }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('dataMgmt.colType')" width="130">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" round class="type-tag">
              {{ dbTypeLabel(row.dbType) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column :label="t('dataMgmt.colEndpoint')" min-width="200">
          <template #default="{ row }">
            <code class="ds-endpoint">{{ row.host }}:{{ row.port }} / {{ row.databaseName }}</code>
          </template>
        </el-table-column>

        <el-table-column :label="t('dataMgmt.colStatus')" width="70" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="(row as Datasource).status === 1"
              :disabled="!auth.hasPerm('data:datasource:update')"
              @change="(v) => toggleStatus(row as Datasource, v)"
            />
          </template>
        </el-table-column>

        <el-table-column :label="t('dataMgmt.colLastTest')" min-width="380">
          <template #default="{ row }">
            <TestResultCell :row="row as Datasource" />
          </template>
        </el-table-column>

        <el-table-column :label="t('dataMgmt.colUpdatedAt')" width="180">
          <template #default="{ row }">
            <span class="muted">{{ formatTime((row as Datasource).updatedAt) }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('common.actions')" width="180" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="auth.hasPerm('data:datasource:test')"
              :icon="Connection"
              size="small"
              link
              type="warning"
              :loading="(row as Datasource).__testing"
              :disabled="!(row as Datasource).status"
              @click="testRow(row as Datasource)"
            >
              {{ t('dataMgmt.testBtn') }}
            </el-button>
            <el-button
              v-if="auth.hasPerm('data:datasource:update')"
              size="small"
              link
              type="primary"
              @click="openEdit(row as Datasource)"
            >
              {{ t('common.edit') }}
            </el-button>
            <el-popconfirm
              v-if="auth.hasPerm('data:datasource:delete')"
              :title="t('dataMgmt.deleteConfirm', { name: (row as Datasource).name })"
              @confirm="remove(row as Datasource)"
            >
              <template #reference>
                <el-button size="small" link type="danger">{{ t('common.delete') }}</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="!loading && rows.length === 0" class="ds-empty">
      <div class="empty-inner">
        <el-icon class="empty-icon"><Coin /></el-icon>
        <h3 class="empty-title">{{ t('dataMgmt.emptyTitle') }}</h3>
        <p class="empty-desc">{{ t('dataMgmt.emptyDesc') }}</p>
        <el-button
          v-if="auth.hasPerm('data:datasource:update')"
          type="primary"
          :icon="Plus"
          @click="openCreate"
        >{{ t('dataMgmt.emptyAction') }}</el-button>
      </div>
    </div>

    <!--
      创建/编辑弹窗：
      label-width 100px 与系统其他页面对齐；
      密码字段采用与 OCR API Key 一致的"三态"模式。
    -->
    <el-dialog
      v-model="dialog.visible"
      :title="dialog.title"
      width="560px"
      append-to-body
      destroy-on-close
      align-center
    >
      <el-form
        :model="form"
        :rules="rules"
        ref="formRef"
        label-width="100px"
        label-position="right"
      >
        <el-form-item :label="t('dataMgmt.fieldName')" prop="name">
          <el-input v-model="form.name" :placeholder="t('dataMgmt.placeholderName')" />
        </el-form-item>

        <el-form-item :label="t('dataMgmt.fieldDbType')" prop="dbType">
          <el-select v-model="form.dbType" style="width: 100%">
            <el-option value="mysql"      :label="t('dataMgmt.dbTypeMysql')" />
            <el-option value="postgresql" :label="t('dataMgmt.dbTypePostgresql')" />
            <el-option value="mssql"      :label="t('dataMgmt.dbTypeMssql')" />
          </el-select>
        </el-form-item>

        <el-form-item :label="t('dataMgmt.fieldHost')" prop="host">
          <el-input v-model="form.host" placeholder="127.0.0.1" />
        </el-form-item>

        <el-form-item :label="t('dataMgmt.fieldPort')" prop="port">
          <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" style="width: 100%" />
        </el-form-item>

        <el-form-item :label="t('dataMgmt.fieldDatabase')" prop="databaseName">
          <el-input v-model="form.databaseName" />
        </el-form-item>

        <el-form-item :label="t('dataMgmt.fieldUsername')" prop="username">
          <el-input v-model="form.username" />
        </el-form-item>

        <el-form-item :label="t('dataMgmt.fieldPassword')" :prop="passwordProp">
          <div class="pwd-row">
            <!-- missing / 正在编辑 → 输入框 -->
            <el-input
              v-if="form.passwordStatus === 'missing' || form.editingPassword"
              ref="pwdInputRef"
              v-model="form.password"
              type="password"
              show-password
              clearable
              @focus="clearPwdMask"
            />
            <!-- ok / failed → 状态卡 + 修改按钮 -->
            <template v-else>
              <el-tag
                :type="form.passwordStatus === 'failed' ? 'warning' : 'success'"
                effect="light"
                round
                class="pwd-state"
              >
                <el-icon style="margin-right: 4px;">
                  <component :is="form.passwordStatus === 'failed' ? WarningFilled : CircleCheckFilled" />
                </el-icon>
                {{ form.passwordStatus === 'failed' ? t('dataMgmt.passwordFailed') : t('dataMgmt.passwordConfigured') }}
              </el-tag>
              <el-button
                link
                type="primary"
                size="small"
                @click="startEditPassword"
              >{{ t('dataMgmt.passwordChange') }}</el-button>
            </template>
          </div>
        </el-form-item>

        <el-form-item :label="t('dataMgmt.fieldRemark')">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
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

      <div class="dialog-test-row">
        <el-button
          v-if="auth.hasPerm('data:datasource:test')"
          type="warning"
          plain
          :icon="Connection"
          :loading="dialog.testing"
          :disabled="!canTestInDialog"
          @click="testInDialog"
        >{{ t('dataMgmt.testBtn') }}</el-button>
        <span v-if="dialog.testResult" class="dialog-test-result" :class="{ ok: dialog.testResult.ok }">
          {{ dialog.testResult.ok
            ? `✓ ${dialog.testResult.latencyMs ?? '?'}ms · ${dialog.testResult.version || ''}`
            : `✗ ${dialog.testResult.message || ''}` }}
        </span>
        <span v-else-if="!canTestInDialog" class="dialog-test-hint muted">
          {{ t('dataMgmt.testDisabledHint') }}
        </span>
      </div>

      <template #footer>
        <el-button @click="dialog.visible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="dialog.loading" @click="save">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { CircleCheckFilled, Coin, Connection, Plus, WarningFilled } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { datasourceApi, type DbType, type Datasource } from '@/api/datasource';
import TestResultCell from './TestResultCell.vue';

const { t } = useI18n();
const auth = useAuthStore();

const rows = ref<Datasource[]>([]);
const loading = ref(false);

const dialog = reactive({
  visible: false,
  mode: 'create' as 'create' | 'edit',
  title: '',
  loading: false,
  testing: false,
  testResult: null as { ok: boolean; version?: string; latencyMs?: number; message?: string } | null,
});

const form = reactive({
  id: 0 as number,
  name: '',
  dbType: 'mysql' as DbType,
  host: '',
  port: 3306,
  databaseName: '',
  username: '',
  password: '',
  passwordStatus: 'missing' as 'missing' | 'ok' | 'failed',
  editingPassword: false,
  remark: '',
  status: 1 as 0 | 1,
});

const formRef = ref<FormInstance>();
const pwdInputRef = ref<{ focus: () => void }>();

const rules = computed<FormRules>(() => ({
  name:         [{ required: true, message: t('common.required') }],
  dbType:       [{ required: true, message: t('common.required') }],
  host:         [{ required: true, message: t('common.required') }],
  port:         [{ required: true, message: t('common.required') }],
  databaseName: [{ required: true, message: t('common.required') }],
  username:     [{ required: true, message: t('common.required') }],
  password:     [{ required: form.passwordStatus === 'missing' || form.editingPassword,
                  message: t('common.required') }],
}));

// 密码校验只在创建态 + 编辑态进入密码编辑模式时启用
const passwordProp = computed(() =>
  form.passwordStatus === 'missing' || form.editingPassword ? 'password' : '');

const canTestInDialog = computed(() =>
  form.host && form.port && form.databaseName && form.username
    && (form.passwordStatus === 'missing' || form.editingPassword ? form.password : true)
);

function dbTypeLabel(t2: DbType) {
  return t2 === 'mysql'      ? t('dataMgmt.dbTypeMysql')
       : t2 === 'mssql'      ? t('dataMgmt.dbTypeMssql')
       :                       t('dataMgmt.dbTypePostgresql');
}

function formatTime(s?: string | null) {
  if (!s) return '—';
  const d = new Date(s);
  if (isNaN(d.getTime())) return s;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

async function reload() {
  loading.value = true;
  try {
    rows.value = await datasourceApi.list();
  } finally {
    loading.value = false;
  }
}

function resetForm() {
  Object.assign(form, {
    id: 0, name: '', dbType: 'mysql', host: '', port: 3306,
    databaseName: '', username: '', password: '',
    passwordStatus: 'missing', editingPassword: false,
    remark: '', status: 1,
  });
}

function openCreate() {
  resetForm();
  dialog.mode = 'create';
  dialog.title = t('dataMgmt.createTitle');
  dialog.testResult = null;
  dialog.visible = true;
}

function openEdit(row: Datasource) {
  dialog.mode = 'edit';
  dialog.title = `${t('dataMgmt.editTitle')}: ${row.name}`;
  Object.assign(form, {
    id: row.id,
    name: row.name,
    dbType: row.dbType,
    host: row.host,
    port: row.port,
    databaseName: row.databaseName,
    username: row.username,
    password: '',
    passwordStatus: row.passwordStatus,
    editingPassword: false,
    remark: row.remark || '',
    status: row.status,
  });
  dialog.testResult = null;
  dialog.visible = true;
}

async function save() {
  if (!formRef.value) return;
  await formRef.value.validate(async (valid) => {
    if (!valid) return;
    dialog.loading = true;
    try {
      const basePayload = {
        name: form.name,
        dbType: form.dbType,
        host: form.host,
        port: form.port,
        databaseName: form.databaseName,
        username: form.username,
        remark: form.remark,
        status: form.status,
      };

      if (dialog.mode === 'create') {
        await datasourceApi.create({ ...basePayload, password: form.password });
        ElMessage.success(t('common.created'));
      } else {
        // 编辑：未进入密码编辑模式时，password 字段置空 → 后端视为保留旧值
        const password = form.editingPassword ? form.password : '';
        await datasourceApi.update(form.id, { ...basePayload, password });
        ElMessage.success(t('common.updated'));
      }
      dialog.visible = false;
      await reload();
    } catch (e: any) {
      ElMessage.error(e?.message || String(e));
    } finally {
      dialog.loading = false;
    }
  });
}

async function remove(row: Datasource) {
  try {
    await datasourceApi.remove(row.id);
    ElMessage.success(t('common.deleted'));
    await reload();
  } catch (e: any) {
    ElMessage.error(e?.message || String(e));
  }
}

async function toggleStatus(row: Datasource, v: boolean | string | number) {
  const status: 0 | 1 = v ? 1 : 0;
  try {
    await datasourceApi.update(row.id, { status });
    ElMessage.success(t('common.updated'));
    await reload();
  } catch (e: any) {
    ElMessage.error(e?.message || String(e));
  }
}

// ===== 行内测试 =====
async function testRow(row: Datasource) {
  if (!row.status) return;
  row.__testing = true;
  try {
    const r = await datasourceApi.test(row.id);
    row.__lastTestResult = r;
    await reload(); // 拿到后端写回的 lastTestOk/lastTestAt/lastTestMsg
  } catch (e: any) {
    row.__lastTestResult = { ok: false, message: e?.message || String(e) };
  } finally {
    row.__testing = false;
  }
}

// ===== 弹窗内测试（创建/编辑前验证） =====
async function testInDialog() {
  if (!canTestInDialog.value) return;
  dialog.testing = true;
  try {
    if (dialog.mode === 'edit') {
      // 已存数据源：走测已存的接口（后端解密 + 测试）
      const r = await datasourceApi.test(form.id);
      dialog.testResult = r;
    } else {
      // 新建表单：用明文直接测
      const r = await datasourceApi.testNew({
        dbType: form.dbType,
        host: form.host,
        port: form.port,
        databaseName: form.databaseName,
        username: form.username,
        password: form.password,
      });
      dialog.testResult = r;
    }
  } catch (e: any) {
    dialog.testResult = { ok: false, message: e?.message || String(e) };
  } finally {
    dialog.testing = false;
  }
}

// ===== 密码三态切换 =====
function startEditPassword() {
  form.editingPassword = true;
  // ok 状态预填掩码，failed 状态直接空（前端 nextTick 后聚焦并清掉掩码）
  form.password = form.passwordStatus === 'ok' ? '********' : '';
  nextTick(() => {
    pwdInputRef.value?.focus?.();
    if (form.password === '********') form.password = '';
  });
}

function clearPwdMask() {
  if (form.password === '********') form.password = '';
}

onMounted(reload);
</script>

<style scoped>
.ds-page .page-header {
  display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 20px;
}
.ds-page .page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; }
.ds-page .page-desc  { margin: 4px 0 0; font-size: 13px; }

/* 列表区：plain 1px 边框容器；避开 SaaS-card 套娃 */
.ds-table-wrap {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-elev);
  overflow: hidden;
}
.ds-table { width: 100%; }
.ds-name { font-weight: 500; color: var(--color-text); }
.type-tag { font-family: ui-monospace, 'JetBrains Mono', monospace; }
.ds-endpoint {
  font-family: ui-monospace, 'JetBrains Mono', monospace;
  font-size: 12px;
  color: var(--color-text-secondary);
  background: var(--color-border-soft);
  padding: 2px 6px;
  border-radius: var(--radius-xs);
}

/* 弹窗密码三态 */
.pwd-row {
  display: flex; align-items: center; gap: 12px; width: 100%;
}
.pwd-row :deep(.el-input) { flex: 1 1 auto; }
.pwd-state { font-size: 12px; }

/* 弹窗底部测试行 */
.dialog-test-row {
  display: flex; align-items: center; gap: 12px;
  padding: 14px 0 4px;
  border-top: 1px solid var(--color-border-soft);
  margin-top: 8px;
}
.dialog-test-result {
  font-size: 12px;
  font-family: ui-monospace, 'JetBrains Mono', monospace;
  color: var(--color-danger);
}
.dialog-test-result.ok { color: var(--color-success); }
.dialog-test-hint { font-size: 12px; }

/* 空状态：紧贴表格容器下方，垂直居中 */
.ds-empty {
  display: flex; align-items: center; justify-content: center;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-sm);
  padding: 64px 24px;
  margin-top: 16px;
  background: var(--color-bg-elev);
}
.empty-inner { text-align: center; max-width: 360px; }
.empty-icon  { font-size: 40px; color: var(--color-text-muted); }
.empty-title { margin: 16px 0 4px; font-size: 16px; font-weight: 600; color: var(--color-text); }
.empty-desc  { margin: 0 0 20px; font-size: 13px; color: var(--color-text-secondary); }
</style>