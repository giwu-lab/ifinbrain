<template>
  <div class="page-container fade-in-up flow-rt-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('design.flowRtTitle') }}</h1>
        <p class="page-desc muted">{{ t('design.flowRtDesc') }}</p>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="flow-rt-tabs">
      <!-- ============== 待办 ============== -->
      <el-tab-pane :label="t('design.flowTabPending')" name="pending">
        <div class="flow-table-wrap">
          <el-table :data="pendingRows" v-loading="loadingPending" border stripe size="small" empty-text="无待办">
            <el-table-column :label="t('design.flowColFlow')" width="180">
              <template #default="{ row }">
                <code class="mono">{{ (row as FlowTaskItem).flowCode }}</code>
                <div class="muted small">{{ (row as FlowTaskItem).flowName }}</div>
              </template>
            </el-table-column>
            <el-table-column :label="t('design.flowColNode')" width="160">
              <template #default="{ row }">
                {{ (row as FlowTaskItem).nodeLabel }}
              </template>
            </el-table-column>
            <el-table-column :label="t('design.flowColBusinessKey')" width="140">
              <template #default="{ row }">
                <code class="mono">{{ (row as FlowTaskItem).businessKey }}</code>
              </template>
            </el-table-column>
            <el-table-column :label="t('design.flowColStartedBy')" width="100">
              <template #default="{ row }">
                {{ (row as FlowTaskItem).startedBy }}
              </template>
            </el-table-column>
            <el-table-column :label="t('design.flowColCreatedAt')" width="160">
              <template #default="{ row }">
                <span class="mono-time">{{ formatTime((row as FlowTaskItem).createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.actions')" :width="180" align="center" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" :icon="Check" @click="openApproveModal(row as FlowTaskItem)" />
                <el-button size="small" type="danger" :icon="Close" @click="openRejectModal(row as FlowTaskItem)" />
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- ============== 我发起的 ============== -->
      <el-tab-pane :label="t('design.flowTabApplied')" name="applied">
        <div class="flow-table-wrap">
          <el-table :data="appliedRows" v-loading="loadingApplied" border stripe size="small" empty-text="尚未发起任何流程">
            <el-table-column :label="t('design.flowColFlow')" width="180">
              <template #default="{ row }">
                <code class="mono">{{ (row as FlowInstanceItem).flowCode }}</code>
                <div class="muted small">{{ (row as FlowInstanceItem).flowName }}</div>
              </template>
            </el-table-column>
            <el-table-column :label="t('design.flowColBusinessKey')" width="140">
              <template #default="{ row }">
                <code class="mono">{{ (row as FlowInstanceItem).businessKey }}</code>
              </template>
            </el-table-column>
            <el-table-column :label="t('design.flowColState')" width="100" align="center">
              <template #default="{ row }">
                <span class="status" :class="(row as FlowInstanceItem).state.toLowerCase()">
                  {{ stateLabel((row as FlowInstanceItem).state) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column :label="t('design.flowColCurrent')" width="160">
              <template #default="{ row }">
                {{ (row as FlowInstanceItem).currentNodeLabel || (row as FlowInstanceItem).currentNodeId || '—' }}
              </template>
            </el-table-column>
            <el-table-column :label="t('design.flowColStartedAt')" width="160">
              <template #default="{ row }">
                <span class="mono-time">{{ formatTime((row as FlowInstanceItem).startedAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('design.flowColFinishedAt')" width="160">
              <template #default="{ row }">
                <span class="mono-time">{{ formatTime((row as FlowInstanceItem).finishedAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.actions')" :width="80" align="center" fixed="right">
              <template #default="{ row }">
                <el-button size="small" link :icon="View" @click="openDetailModal(row as FlowInstanceItem)" />
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- ============== 发起流程 ============== -->
      <el-tab-pane :label="t('design.flowTabStart')" name="start">
        <div class="flow-start-wrap">
          <el-form label-position="top" size="default" :model="startForm" class="start-form">
            <el-form-item :label="t('design.flowColFlow')">
              <el-select v-model="startForm.code" :placeholder="t('design.flowStartPickFlow')" filterable style="width: 100%;">
                <el-option
                  v-for="f in publishedFlows"
                  :key="f.id"
                  :label="`${f.name} (${f.code})`"
                  :value="f.code"
                />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('design.flowColBusinessKey')">
              <el-input v-model="startForm.businessKey" :placeholder="t('design.flowStartBusinessKeyPh')" style="width: 100%;" />
            </el-form-item>
            <el-form-item :label="t('design.flowStartVariables')">
              <el-input v-model="startForm.variablesJson" type="textarea" :rows="6" :placeholder="t('design.flowStartVariablesPh')" style="width: 100%; font-family: monospace;" />
              <div class="muted small">{{ t('design.flowStartVariablesHint') }}</div>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :icon="Promotion" :loading="starting" @click="onStart">{{ t('design.flowStartSubmit') }}</el-button>
            </el-form-item>
          </el-form>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 审批 modal -->
    <el-dialog v-model="approveVisible" :title="t('design.flowApproveTitle')" width="480px">
      <el-form label-position="top">
        <el-form-item :label="t('design.flowApproveComment')">
          <el-input v-model="approveComment" type="textarea" :rows="4" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="approveVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="approveSubmitting" @click="onApproveSubmit">{{ t('design.flowApproveOk') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rejectVisible" :title="t('design.flowRejectTitle')" width="480px">
      <el-form label-position="top">
        <el-form-item :label="t('design.flowApproveComment')">
          <el-input v-model="approveComment" type="textarea" :rows="4" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="danger" :loading="approveSubmitting" @click="onRejectSubmit">{{ t('design.flowRejectOk') }}</el-button>
      </template>
    </el-dialog>

    <!-- 详情 modal -->
    <el-dialog v-model="detailVisible" :title="t('design.flowDetailTitle')" width="640px">
      <div v-if="detailInst">
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item :label="t('design.flowColFlow')">{{ detailInst.flowCode }} / {{ detailInst.flowName }}</el-descriptions-item>
          <el-descriptions-item :label="t('design.flowColBusinessKey')"><code class="mono">{{ detailInst.businessKey }}</code></el-descriptions-item>
          <el-descriptions-item :label="t('design.flowColState')">
            <span class="status" :class="detailInst.state.toLowerCase()">{{ stateLabel(detailInst.state) }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('design.flowColStartedBy')">{{ detailInst.startedBy }}</el-descriptions-item>
          <el-descriptions-item :label="t('design.flowColCurrent')">{{ detailInst.currentNodeLabel || detailInst.currentNodeId || '—' }}</el-descriptions-item>
          <el-descriptions-item :label="t('design.flowColStartedAt')">{{ formatTime(detailInst.startedAt) }}</el-descriptions-item>
          <el-descriptions-item :label="t('design.flowColFinishedAt')" :span="2">{{ formatTime(detailInst.finishedAt) }}</el-descriptions-item>
          <el-descriptions-item :label="t('design.flowStartVariables')" :span="2">
            <pre class="var-pre mono">{{ JSON.stringify(detailInst.variables || {}, null, 2) }}</pre>
          </el-descriptions-item>
        </el-descriptions>
        <h4 class="history-title">{{ t('design.flowHistoryTitle') }}</h4>
        <el-table :data="detailInst.history || []" border size="small" empty-text="无">
          <el-table-column :label="t('design.flowColAction')" width="80" prop="action" />
          <el-table-column :label="t('design.flowColNode')" width="140">
            <template #default="{ row }">{{ (row as any).nodeLabel || (row as any).nodeId }}</template>
          </el-table-column>
          <el-table-column :label="t('design.flowColOperator')" width="100" prop="operator" />
          <el-table-column :label="t('design.flowColComment')" prop="comment" />
          <el-table-column :label="t('design.flowColCreatedAt')" width="160">
            <template #default="{ row }"><span class="mono-time">{{ formatTime((row as any).createdAt) }}</span></template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Check, Close, View, Promotion } from '@element-plus/icons-vue';
import { useI18n } from 'vue-i18n';
import { useAuthStore } from '@/stores/auth';
import { flowTaskApi, flowInstanceApi, type FlowTaskItem, type FlowInstanceItem } from '@/api/flowTask';
import { flowDefineApi, type FlowDefineItem } from '@/api/flowDefine';
import { formatDateTime } from '@/utils/time';

const { t } = useI18n();
const auth = useAuthStore();

const activeTab = ref<'pending' | 'applied' | 'start'>('pending');

// 待办
const pendingRows = ref<FlowTaskItem[]>([]);
const loadingPending = ref(false);

async function loadPending() {
  loadingPending.value = true;
  try {
    pendingRows.value = await flowTaskApi.pending();
  } catch (e: any) { ElMessage.error(e?.response?.data?.message || String(e)); }
  finally { loadingPending.value = false; }
}

// 我发起的
const appliedRows = ref<FlowInstanceItem[]>([]);
const loadingApplied = ref(false);
async function loadApplied() {
  loadingApplied.value = true;
  try {
    const all = await flowInstanceApi.list();
    appliedRows.value = all.filter(i => i.startedBy === auth.user?.username);
  } catch (e: any) { ElMessage.error(e?.response?.data?.message || String(e)); }
  finally { loadingApplied.value = false; }
}

// 发起流程
const publishedFlows = ref<FlowDefineItem[]>([]);
const startForm = reactive({ code: '', businessKey: '', variablesJson: '{}' });
const starting = ref(false);

async function loadFlows() {
  try {
    const all = await flowDefineApi.list();
    publishedFlows.value = all.filter(f => f.status === 1);
  } catch {}
}

async function onStart() {
  if (!startForm.code) { ElMessage.warning('请选择流程'); return; }
  if (!startForm.businessKey) { ElMessage.warning('请输入 businessKey'); return; }
  let vars: Record<string, any> = {};
  try { vars = JSON.parse(startForm.variablesJson || '{}'); } catch { ElMessage.error('变量 JSON 格式错误'); return; }
  starting.value = true;
  try {
    const inst = await flowInstanceApi.start({ code: startForm.code, businessKey: startForm.businessKey, variables: vars });
    ElMessage.success(t('design.flowStartSuccess', { id: inst.id }));
    startForm.businessKey = '';
    startForm.variablesJson = '{}';
    activeTab.value = 'applied';
    await loadApplied();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  } finally { starting.value = false; }
}

// 审批 modal
const approveVisible = ref(false);
const rejectVisible = ref(false);
const currentTask = ref<FlowTaskItem | null>(null);
const approveComment = ref('');
const approveSubmitting = ref(false);

function openApproveModal(row: FlowTaskItem) {
  currentTask.value = row;
  approveComment.value = '';
  approveVisible.value = true;
}
function openRejectModal(row: FlowTaskItem) {
  currentTask.value = row;
  approveComment.value = '';
  rejectVisible.value = true;
}

async function onApproveSubmit() {
  if (!currentTask.value) return;
  approveSubmitting.value = true;
  try {
    await flowTaskApi.approve(currentTask.value.id, { comment: approveComment.value });
    ElMessage.success(t('design.flowApproved'));
    approveVisible.value = false;
    await loadPending();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  } finally { approveSubmitting.value = false; }
}

async function onRejectSubmit() {
  if (!currentTask.value) return;
  approveSubmitting.value = true;
  try {
    await flowTaskApi.reject(currentTask.value.id, { comment: approveComment.value });
    ElMessage.success(t('design.flowRejected'));
    rejectVisible.value = false;
    await loadPending();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  } finally { approveSubmitting.value = false; }
}

// 详情 modal
const detailVisible = ref(false);
const detailInst = ref<FlowInstanceItem | null>(null);
async function openDetailModal(row: FlowInstanceItem) {
  detailVisible.value = true;
  try {
    detailInst.value = await flowInstanceApi.detail(row.id);
  } catch (e: any) {
    detailInst.value = row;
    ElMessage.warning(t('design.flowDetailPartial'));
  }
}

// 工具
function formatTime(s?: string | null) { return formatDateTime(s); }
function stateLabel(s: string): string {
  const map: Record<string, string> = {
    RUNNING: t('design.flowStateRunning'),
    APPROVED: t('design.flowStateApproved'),
    REJECTED: t('design.flowStateRejected'),
    CANCELED: t('design.flowStateCanceled'),
  };
  return map[s] || s;
}

onMounted(async () => {
  await Promise.all([loadPending(), loadApplied(), loadFlows()]);
});
</script>

<style scoped>
.flow-rt-page .page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 16px;
}
.flow-rt-page .page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; }
.flow-rt-page .page-desc  { margin: 4px 0 0; font-size: 13px; }

.flow-table-wrap { margin-top: 16px; }
.flow-rt-page :deep(th.el-table__cell) {
  position: sticky; top: 92px; z-index: 20;
  background: var(--color-bg) !important;
}

.status {
  font-size: 11px; padding: 1px 8px; border-radius: 2px; font-weight: 500;
}
.status.running  { background: #e6f4ea; color: #0e8f5c; }
.status.approved { background: #e3f2fd; color: #1565c0; }
.status.rejected { background: #fde7e9; color: #c62828; }
.status.canceled { background: #f0f0eb; color: #8a8a85; }

.flow-start-wrap {
  padding: 24px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-elev);
  margin-top: 16px;
  max-width: 720px;
}
.start-form { width: 100%; }

.var-pre {
  margin: 0;
  padding: 8px;
  font-size: 12px;
  background: var(--color-bg);
  border-radius: var(--radius-xs);
  max-height: 160px;
  overflow: auto;
}
.history-title { font-size: 13px; font-weight: 600; margin: 16px 0 8px; }
</style>