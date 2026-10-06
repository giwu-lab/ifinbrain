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
        <!--
          上半区：我发起的被驳回实例（需要修改后重新提交）
          只在 rejectedMy.length > 0 时显示 section
        -->
        <template v-if="rejectedMy.length">
          <h4 class="section-title">
            <el-icon class="danger-text"><CircleClose /></el-icon>
            {{ t('design.flowRejectedMyTitle') }} ({{ rejectedMy.length }})
          </h4>
          <div class="flow-table-wrap">
            <el-table :data="rejectedMy" border stripe size="small">
              <el-table-column :label="t('design.flowColFlow')" min-width="180">
                <template #default="{ row }">
                  <code class="mono">{{ (row as FlowInstanceItem).flowCode }}</code>
                  <div class="muted small">{{ (row as FlowInstanceItem).flowName }}</div>
                </template>
              </el-table-column>
              <el-table-column :label="t('design.flowColBusinessKey')" min-width="140">
                <template #default="{ row }">
                  <code class="mono">{{ (row as FlowInstanceItem).businessKey }}</code>
                </template>
              </el-table-column>
              <el-table-column :label="t('design.flowColCurrent')" min-width="160">
                <template #default="{ row }">
                  {{ (row as FlowInstanceItem).currentNodeLabel || (row as FlowInstanceItem).currentNodeId || '—' }}
                </template>
              </el-table-column>
              <el-table-column :label="t('design.flowColFinishedAt')" min-width="160">
                <template #default="{ row }">
                  <span class="mono-time">{{ formatTime((row as FlowInstanceItem).finishedAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('common.actions')" :width="160" align="center" fixed="right">
                <template #default="{ row }">
                  <el-tooltip :content="t('design.flowDetailTitle')" placement="top">
                    <el-button size="small" link :icon="View" @click="openDetailModal(row as FlowInstanceItem)" />
                  </el-tooltip>
                  <el-tooltip :content="t('design.flowResubmit')" placement="top">
                    <el-button size="small" link :icon="RefreshRight" @click="onResubmit(row as FlowInstanceItem)" />
                  </el-tooltip>
                  <el-dropdown trigger="click" @command="(cmd: string) => onCmd(cmd, row as FlowInstanceItem)">
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
          </div>
        </template>

        <!-- 下半区：待审批任务 -->
        <h4 class="section-title">{{ t('design.flowTasksMyTitle') }}</h4>
        <div class="flow-table-wrap">
          <el-table :data="pendingRows" v-loading="loadingPending" border stripe size="small">
            <el-table-column :label="t('design.flowColFlow')" min-width="180">
              <template #default="{ row }">
                <code class="mono">{{ (row as FlowTaskItem).flowCode }}</code>
                <div class="muted small">{{ (row as FlowTaskItem).flowName }}</div>
              </template>
            </el-table-column>
            <el-table-column :label="t('design.flowColNode')" min-width="160">
              <template #default="{ row }">
                {{ (row as FlowTaskItem).nodeLabel }}
              </template>
            </el-table-column>
            <el-table-column :label="t('design.flowColBusinessKey')" min-width="140">
              <template #default="{ row }">
                <code class="mono">{{ (row as FlowTaskItem).businessKey }}</code>
              </template>
            </el-table-column>
            <el-table-column :label="t('design.flowColStartedBy')" min-width="100">
              <template #default="{ row }">
                {{ (row as FlowTaskItem).startedBy }}
              </template>
            </el-table-column>
            <el-table-column :label="t('design.flowColCreatedAt')" min-width="160">
              <template #default="{ row }">
                <span class="mono-time">{{ formatTime((row as FlowTaskItem).createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.actions')" :width="80" align="center" fixed="right">
              <template #default="{ row }">
                <el-tooltip :content="t('design.flowProcessBtn')" placement="top">
                  <el-button size="small" link :icon="Edit" @click="openDetailFromTask(row as FlowTaskItem)" />
                </el-tooltip>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty">
                <el-icon class="empty-icon"><BellFilled /></el-icon>
                <p class="muted">{{ t('design.flowPendingEmpty') }}</p>
              </div>
            </template>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- ============== 我发起的 ============== -->
      <el-tab-pane :label="t('design.flowTabApplied')" name="applied">
        <div class="flow-table-wrap">
          <el-table :data="appliedRows" v-loading="loadingApplied" border stripe size="small">
            <el-table-column :label="t('design.flowColFlow')" min-width="180">
              <template #default="{ row }">
                <code class="mono">{{ (row as FlowInstanceItem).flowCode }}</code>
                <div class="muted small">{{ (row as FlowInstanceItem).flowName }}</div>
              </template>
            </el-table-column>
            <el-table-column :label="t('design.flowColBusinessKey')" min-width="140">
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
            <el-table-column :label="t('design.flowColCurrent')" min-width="160">
              <template #default="{ row }">
                {{ (row as FlowInstanceItem).currentNodeLabel || (row as FlowInstanceItem).currentNodeId || '—' }}
              </template>
            </el-table-column>
            <el-table-column :label="t('design.flowColStartedAt')" min-width="160">
              <template #default="{ row }">
                <span class="mono-time">{{ formatTime((row as FlowInstanceItem).startedAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('design.flowColFinishedAt')" min-width="160">
              <template #default="{ row }">
                <span class="mono-time">{{ formatTime((row as FlowInstanceItem).finishedAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.actions')" :width="160" align="center" fixed="right">
              <template #default="{ row }">
                <el-tooltip :content="t('design.flowDetailTitle')" placement="top">
                  <el-button size="small" link :icon="View" @click="openDetailModal(row as FlowInstanceItem)" />
                </el-tooltip>
                <el-tooltip v-if="(row as FlowInstanceItem).state === 'REJECTED'" :content="t('design.flowResubmit')" placement="top">
                  <el-button size="small" link :icon="RefreshRight" @click="onResubmit(row as FlowInstanceItem)" />
                </el-tooltip>
                <el-dropdown v-if="(row as FlowInstanceItem).state === 'REJECTED'" trigger="click" @command="(cmd: string) => onCmd(cmd, row as FlowInstanceItem)">
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
                <el-icon class="empty-icon"><Promotion /></el-icon>
                <p class="muted">{{ t('design.flowAppliedEmpty') }}</p>
              </div>
            </template>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- ============== 发起流程 ============== -->
      <el-tab-pane :label="t('design.flowTabStart')" name="start">
        <!--
          发起流程 tab：流程清单（后端按 perm 过滤）+ 弹窗填表单
          - 列表只显示当前用户有 deploy perm 的已发布流程
          - 点击「发起」按钮 → 弹窗内填 businessKey + 流程表单
          - 「重新发起」也走同一弹窗（驳回后复用）
        -->
        <div class="flow-table-wrap">
          <el-table :data="launchableFlows" v-loading="loadingLaunchable" border stripe size="small">
            <el-table-column :label="t('design.flowColFlow')" min-width="220">
              <template #default="{ row }">
                <div class="flow-name-cell">
                  <span class="flow-name">{{ (row as FlowDefineItem).name }}</span>
                  <code class="mono muted small">{{ (row as FlowDefineItem).code }}</code>
                  <div v-if="(row as FlowDefineItem).description" class="muted small flow-desc">
                    {{ (row as FlowDefineItem).description }}
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column :label="t('design.flowColNodes')" width="80" align="center">
              <template #default="{ row }">
                <span class="tabular">{{ (row as FlowDefineItem).nodeCount ?? 0 }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('design.flowColEdges')" width="80" align="center">
              <template #default="{ row }">
                <span class="tabular">{{ (row as FlowDefineItem).edgeCount ?? 0 }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.actions')" width="100" align="center" fixed="right">
              <template #default="{ row }">
                <el-tooltip :content="t('design.flowStartBtn')" placement="top">
                  <el-button size="small" link :icon="Promotion" @click="openStartDialog(row as FlowDefineItem)" />
                </el-tooltip>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty">
                <el-icon class="empty-icon"><DocumentRemove /></el-icon>
                <p class="muted">{{ t('design.flowLaunchableEmpty') }}</p>
              </div>
            </template>
          </el-table>
        </div>

        <!-- 发起流程弹窗：填业务表单 -->
        <el-dialog
          v-model="startVisible"
          :title="startDialogTitle"
          width="640px"
          align-center
          custom-class="flow-start-dialog"
          :close-on-click-modal="false"
          @close="closeStartDialog"
        >
          <el-form v-if="pickedFlow" label-position="top" size="default" :model="startForm" class="start-form">
            <el-form-item :label="t('design.flowColBusinessKey')" required>
              <el-input
                v-model="startForm.businessKey"
                :placeholder="t('design.flowStartBusinessKeyPh')"
              />
            </el-form-item>
            <el-form-item :label="t('design.flowStartVariables')">
              <!--
                启动表单：动态渲染。
                - openStartDialog → 拉 pickedFlow detail → 找 START 节点 → 取 formCode
                  （节点覆盖 > 流程默认 defaultFormCode）
                - 拉 formDesign detailByCode → FormRenderer 用 startFormData
                - 提交时把 startFormData 作为 variables 传给 start API
              -->
              <div v-if="startFormLoading" class="form-loading">
                <el-icon class="is-loading"><Loading /></el-icon>
                <span class="muted">{{ t('design.flowFormLoading') }}</span>
              </div>
              <FormRenderer
                v-else-if="startFormFields.length"
                :fields="startFormFields"
                :model-value="startFormData"
                @update:model-value="(v) => startFormData = v"
                class="start-form-renderer"
              />
              <div v-else class="form-empty muted">
                {{ t('design.flowFormEmpty') }}
              </div>
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="startVisible = false">{{ t('common.cancel') }}</el-button>
            <el-button type="primary" :loading="starting" @click="onStartSubmit">{{ t('design.flowStartSubmit') }}</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>
    </el-tabs>

    <!-- 审批 / 详情合并 modal -->
    <el-dialog v-model="detailVisible" :title="t('design.flowDetailTitle')" width="720px" align-center custom-class="flow-detail-dialog">
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
        </el-descriptions>

        <!-- 流程表单：根据填发起人姓名 + 当前 task 节点 formCode 动态渲染（readonly） -->
        <h4 class="history-title">{{ t('design.flowFormTitle') }}</h4>
        <div class="flow-form-wrap">
          <div v-if="taskFormLoading" class="form-loading">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span class="muted">{{ t('design.flowFormLoading') }}</span>
          </div>
          <FormRenderer
            v-else-if="taskFormFields.length"
            :fields="taskFormFields"
            :model-value="taskFormData"
            readonly
            class="task-form-renderer"
          />
          <div v-else class="form-empty muted">{{ t('design.flowFormEmpty') }}</div>
        </div>

        <h4 class="history-title">{{ t('design.flowHistoryTitle') }}</h4>
        <div class="history-table-wrap">
          <el-table :data="detailInst.history || []" border stripe size="small">
            <el-table-column :label="t('design.flowColAction')" width="80" align="center" prop="action" />
            <el-table-column :label="t('design.flowColNode')" width="120">
              <template #default="{ row }">{{ (row as any).nodeLabel || (row as any).nodeId }}</template>
            </el-table-column>
            <el-table-column :label="t('design.flowColOperator')" width="80" prop="operator" />
            <el-table-column :label="t('design.flowColComment')" min-width="180" prop="comment" />
            <el-table-column :label="t('design.flowColCreatedAt')" width="140">
              <template #default="{ row }"><span class="mono-time">{{ formatTime((row as any).createdAt) }}</span></template>
            </el-table-column>
            <template #empty>
              <div class="empty">
                <p class="muted">{{ t('design.flowHistoryEmpty') }}</p>
              </div>
            </template>
          </el-table>
        </div>
      </div>

      <!-- 审批模式（pending tab 进入）：底部审批意见 + 同意/驳回 -->
      <template v-if="currentTask" #footer>
        <div class="approve-footer">
          <el-input
            v-model="approveComment"
            type="textarea"
            :rows="2"
            :placeholder="t('design.flowApproveCommentPh')"
            class="approve-comment"
          />
          <div class="approve-actions">
            <el-button @click="detailVisible = false">{{ t('common.cancel') }}</el-button>
            <el-button type="danger" :loading="approveSubmitting" @click="onRejectSubmit">{{ t('design.flowRejectOk') }}</el-button>
            <el-button type="primary" :loading="approveSubmitting" @click="onApproveSubmit">{{ t('design.flowApproveOk') }}</el-button>
          </div>
        </div>
      </template>
      <!-- 只读模式（applied tab 进入）：只显示关闭按钮 -->
      <template v-else #footer>
        <el-button @click="detailVisible = false">{{ t('common.close') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Check, Close, CircleClose, Delete, DocumentRemove, Edit, Promotion, View, BellFilled, Loading, RefreshRight, MoreFilled } from '@element-plus/icons-vue';
import { useI18n } from 'vue-i18n';
import { useRoute } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { flowTaskApi, flowInstanceApi, type FlowTaskItem, type FlowInstanceItem } from '@/api/flowTask';
import { flowDefineApi, type FlowDefineItem } from '@/api/flowDefine';
import { formDesignApi, type FormFieldSchema } from '@/api/formDesign';
import FormRenderer from '@/components/form/FormRenderer.vue';
import { formatDateTime } from '@/utils/time';

const { t } = useI18n();
const auth = useAuthStore();
const route = useRoute();

const activeTab = ref<'pending' | 'applied' | 'start'>('pending');

// 待办
const pendingRows = ref<FlowTaskItem[]>([]);
const loadingPending = ref(false);
const rejectedMy = ref<FlowInstanceItem[]>([]);

async function loadPending() {
  loadingPending.value = true;
  try {
    // 待办 = 当前用户审批任务 + 我发起的被驳回实例（需要修改后重新提交）
    const [tasks, allInsts] = await Promise.all([
      flowTaskApi.pending(),
      flowInstanceApi.list().catch(() => [] as FlowInstanceItem[]),
    ]);
    pendingRows.value = tasks;
    rejectedMy.value = (allInsts || []).filter(
      i => i.startedBy === auth.user?.username && i.state === 'REJECTED'
    );
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

// 发起流程：列表（按 perm 过滤）+ 弹窗填表单
const launchableFlows = ref<FlowDefineItem[]>([]);
const loadingLaunchable = ref(false);
const startVisible = ref(false);
const pickedFlow = ref<FlowDefineItem | null>(null);
const startForm = reactive({ businessKey: '' });
const startFormFields = ref<FormFieldSchema[]>([]);
const startFormData = ref<Record<string, any>>({});
const startFormLoading = ref(false);
const starting = ref(false);

const startDialogTitle = computed(() => {
  const name = pickedFlow.value?.name ?? '';
  return t('design.flowStartDialogTitle', { name });
});

/** 拉当前用户「可发起」的流程清单（后端按 perm 过滤） */
async function loadLaunchable() {
  loadingLaunchable.value = true;
  try {
    launchableFlows.value = await flowDefineApi.launchable();
  } catch (e: any) {
    launchableFlows.value = [];
    ElMessage.error(e?.response?.data?.message || String(e));
  } finally { loadingLaunchable.value = false; }
}

/**
 * 打开发起弹窗：设 pickedFlow + 拉 START 节点 formCode → 拉表单 fields。
 * 若 row 为 null（极少情况）—— 不做任何事。
 */
async function openStartDialog(row: FlowDefineItem | null) {
  if (!row) return;
  pickedFlow.value = row;
  startForm.businessKey = '';
  startFormData.value = {};
  startFormFields.value = [];
  startVisible.value = true;
  startFormLoading.value = true;
  try {
    const def = await flowDefineApi.detailByCode(row.code);
    const startNode = def.flowJson?.nodes?.find(n => n.data.type === 'START');
    const formCode = startNode?.data?.formCode || def.defaultFormCode;
    if (formCode) {
      const form = await formDesignApi.detailByCode(formCode);
      startFormFields.value = form.fields || [];
    } else {
      startFormFields.value = [];
    }
  } catch (e: any) {
    startFormFields.value = [];
    ElMessage.warning(e?.response?.data?.message || String(e));
  } finally { startFormLoading.value = false; }
}

/** 弹窗关闭：清 pickedFlow / formData / formFields */
function closeStartDialog() {
  startVisible.value = false;
  pickedFlow.value = null;
  startForm.businessKey = '';
  startFormData.value = {};
  startFormFields.value = [];
}

async function onStartSubmit() {
  if (!pickedFlow.value) return;
  if (!startForm.businessKey) {
    ElMessage.warning(t('design.flowStartBusinessKeyRequired'));
    return;
  }
  starting.value = true;
  try {
    const inst = await flowInstanceApi.start({
      code: pickedFlow.value.code,
      businessKey: startForm.businessKey,
      variables: { ...startFormData.value },
    });
    ElMessage.success(t('design.flowStartSuccess', { id: inst.id }));
    closeStartDialog();
    activeTab.value = 'applied';
    await Promise.all([loadApplied(), loadLaunchable()]);
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  } finally { starting.value = false; }
}

/**
 * 驳回流程的重新发起：找到对应流程 → 直接打开发起弹窗（不需要再选一次流程）。
 * formCode 由该流程当前的 START 节点决定 —— 若流程改了，重新发起用的是新表单。
 * 变量值从原 instance 回填，businessKey 由用户重填（后端要求唯一）。
 */
async function onResubmit(row: FlowInstanceItem) {
  // launchableFlows 是按当前 perm 过滤的；若 row.flowCode 不在 list（被收回 deploy / 流程下架），
  // 给出明确提示而不是默默打开空弹窗。
  const flow = launchableFlows.value.find(f => f.code === row.flowCode);
  if (!flow) {
    ElMessage.warning(t('design.flowStartFlowUnavailable', { code: row.flowCode }));
    activeTab.value = 'start';
    await loadLaunchable();
    return;
  }
  activeTab.value = 'start';
  await openStartDialog(flow);
  // 在 formFields 已加载后回填原 instance 变量
  startFormData.value = { ...(row.variables || {}) };
  ElMessage.info(t('design.flowResubmitHint', { code: row.flowCode, key: row.businessKey }));
}

/**
 * 表格操作列统一入口（applied tab + rejectedMy section）：危险操作收纳到 ··· 下拉。
 * 当前仅 delete：后端限定仅 REJECTED 状态 + 发起人或 admin 可删。
 */
async function onCmd(cmd: string, row: FlowInstanceItem) {
  if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(
        t('design.flowInstanceDeleteConfirm', { code: row.flowCode, key: row.businessKey }),
        t('common.confirmDelete'),
        { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') }
      );
      await flowInstanceApi.remove(row.id);
      ElMessage.success(t('common.deleted'));
      // 刷新两个 tab 数据
      await Promise.all([loadPending(), loadApplied()]);
    } catch { /* 用户取消 */ }
  }
}

// 详情 modal（合并审批 + 查看）
const detailVisible = ref(false);
const detailInst = ref<FlowInstanceItem | null>(null);
const currentTask = ref<FlowTaskItem | null>(null);   // 仅 pending tab 进入时设置；applied tab 进入为 null
const approveComment = ref('');
const approveSubmitting = ref(false);

/** 流程表单：当前 task 节点 formCode 渲染的 fields + 已填变量值（readonly） */
const taskFormFields = ref<FormFieldSchema[]>([]);
const taskFormData = ref<Record<string, any>>({});
const taskFormLoading = ref(false);

/** 我发起的 tab 进入详情：只读模式 */
async function openDetailModal(row: FlowInstanceItem) {
  currentTask.value = null;
  approveComment.value = '';
  taskFormFields.value = [];
  taskFormData.value = {};
  detailVisible.value = true;
  try {
    detailInst.value = await flowInstanceApi.detail(row.id);
    await loadFormForView(detailInst.value, null);
  } catch (e: any) {
    detailInst.value = row;
    ElMessage.warning(t('design.flowDetailPartial'));
  }
}

/** 待办 tab 进入详情：审批模式（带审批意见 + 同意/驳回按钮） */
async function openDetailFromTask(task: FlowTaskItem) {
  currentTask.value = task;
  approveComment.value = '';
  taskFormFields.value = [];
  taskFormData.value = {};
  detailVisible.value = true;
  detailInst.value = null;
  try {
    const inst = await flowInstanceApi.detail(task.instanceId);
    detailInst.value = inst;
    await loadFormForView(inst, task);
  } catch (e: any) {
    ElMessage.warning(t('design.flowDetailPartial'));
  }
}

/**
 * 加载当前节点的流程表单：
 *  - 拉 flowDefine detailByCode(inst.flowCode) 拿到 flowJson
 *  - 找 curNodeId 对应节点 → 取 formCode（节点覆盖 > 流程默认）
 *  - 拉 formDesign detailByCode 拿 fields
 *  - 用 inst.variables 作 initialValue 回填
 */
async function loadFormForView(inst: FlowInstanceItem, task: FlowTaskItem | null) {
  const curNodeId = task?.nodeId || inst.currentNodeId;
  if (!inst.flowCode || !curNodeId) {
    taskFormFields.value = [];
    return;
  }
  taskFormLoading.value = true;
  try {
    const def = await flowDefineApi.detailByCode(inst.flowCode);
    const curNode = def.flowJson?.nodes?.find(n => n.id === curNodeId);
    const formCode = curNode?.data?.formCode || def.defaultFormCode;
    if (formCode) {
      const form = await formDesignApi.detailByCode(formCode);
      taskFormFields.value = form.fields || [];
      taskFormData.value = { ...(inst.variables || {}) };
    } else {
      taskFormFields.value = [];
    }
  } catch (e: any) {
    taskFormFields.value = [];
  } finally { taskFormLoading.value = false; }
}

async function onApproveSubmit() {
  if (!currentTask.value) return;
  approveSubmitting.value = true;
  try {
    await flowTaskApi.approve(currentTask.value.id, { comment: approveComment.value });
    ElMessage.success(t('design.flowApproved'));
    detailVisible.value = false;
    currentTask.value = null;
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
    detailVisible.value = false;
    currentTask.value = null;
    await loadPending();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  } finally { approveSubmitting.value = false; }
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
  await Promise.all([loadPending(), loadApplied(), loadLaunchable()]);
});

/**
 * Deep-link 入口：通知中心点击某条待审批 → 跳 /design/flow-runtime?taskId=X
 * 监听 route.query.taskId → 拉该任务 → 自动打开审批弹窗
 * 支持在抽屉里连续切换多个 task（query 变化时重新打开弹窗）
 */
watch(
  () => route.query.taskId,
  async (tid) => {
    if (!tid) return;
    const taskId = Number(tid);
    if (!Number.isFinite(taskId) || taskId <= 0) return;
    // 等 pending 列表加载完成，再去 detail（避免乐观空列表）
    await loadPending();
    try {
      const task = await flowTaskApi.detail(taskId);
      // 已处理/已被别人接走的 task：detail 返回的 status 不再是 PENDING
      if (task.status !== 'PENDING') {
        ElMessage.warning(t('design.flowTaskAlreadyDone'));
        return;
      }
      await openDetailFromTask(task);
    } catch (e: any) {
      ElMessage.warning(e?.message || t('design.flowTaskLoadFail'));
    }
  },
  { immediate: true }
);
</script>

<style scoped>
.flow-rt-page .page-header {
  display: flex; justify-content: space-between; align-items: flex-end;
  position: sticky; top: 0; z-index: 30;
  background: var(--color-bg);
  padding: 8px 0; margin-bottom: 0;
}
.flow-rt-page .page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; line-height: 1.3; }
.flow-rt-page .page-desc  { margin: 4px 0 0; font-size: 13px; line-height: 1.4; }

/* 让弹窗打开时背景一致：page 自己撑满 viewport（减去 64px header + 48px 上下 padding），
   否则内容少时 main-inner 高度限制 + var(--color-bg) 会让弹窗下方出现大片"异样灰色" */
.flow-rt-page {
  min-height: calc(100vh - 64px - 48px);
  box-sizing: border-box;
}

/* 列表区：plain 1px 边框容器；避开 SaaS-card 套娃 */
.flow-table-wrap {
  margin-top: 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-elev);
  overflow: visible;
}

/* 流程状态：用 theme token，避开硬编码十六进制 */
.status {
  font-size: 11px; padding: 1px 8px; border-radius: 2px; font-weight: 500;
}
.status.running  { background: var(--color-info-soft);    color: var(--color-info); }
.status.approved { background: var(--color-success-soft); color: var(--color-success); }
.status.rejected { background: var(--color-danger-soft);  color: var(--color-danger); }
.status.canceled { background: var(--color-border-soft);  color: var(--color-text-muted); }

/* 待办 tab 内 section 标题 */
.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
margin: 8px 0 4px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.section-title:first-child { margin-top: 0; }

/* 空状态：紧凑卡片 */
.flow-rt-page .empty {
  display: flex; flex-direction: column; align-items: center;
  padding: 24px 10px; gap: 8px;
}
.flow-rt-page .empty-icon { font-size: 40px; color: var(--color-text-muted); }

/* 流程清单（start tab）：name + code + description 三行布局 */
.flow-name-cell { display: flex; flex-direction: column; gap: 2px; padding: 4px 0; }
.flow-name { font-weight: 500; color: var(--color-text); }
.flow-name-cell .mono { font-size: 12px; }
.flow-desc {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 480px;
  line-height: 1.4;
}

/* 发起流程弹窗：与详情弹窗同款 max-height + 内部滚动 */
.flow-start-dialog { max-height: 80vh; }
.flow-start-dialog .el-dialog__body {
  max-height: calc(80vh - 180px);
  overflow-y: auto;
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
  /* 弹窗内 pre 不撑爆容器 */
  max-width: 100%;
  white-space: pre-wrap;
  word-break: break-all;
}
.history-title { font-size: 13px; font-weight: 600; margin: 16px 0 8px; }

/* modal 内 history 表格：同款 1px 边框容器 */
.history-table-wrap {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-elev);
  overflow: visible;
}

/* 详情弹窗：限制高度 + 内部滚动 + 让弹窗整体"浮起" */
.flow-detail-dialog {
  max-height: 80vh;
}
.flow-detail-dialog .el-dialog__body {
  max-height: calc(80vh - 200px);   /* 加大预留：底部 footer 含审批意见 + 按钮 */
  overflow-y: auto;
}

/* 审批 footer：意见输入 + 按钮组 */
.approve-footer {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.approve-comment { width: 100%; }
.approve-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* 流程表单区块：1px 边框容器 */
.flow-form-wrap,
.start-form-renderer,
.task-form-renderer {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-elev);
  padding: 8px;
  margin-bottom: 8px;
}
.form-empty {
  padding: 12px 8px;
  text-align: center;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-elev);
}
.form-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px;
  color: var(--color-text-secondary);
  font-size: 13px;
}
</style>