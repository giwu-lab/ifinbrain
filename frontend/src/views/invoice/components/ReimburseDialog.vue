<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('invoice.reimburseTitle', { count })"
    width="720px"
    class="reimburse-dialog"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <el-form :model="form" label-width="100px" @submit.prevent="confirm">
      <el-form-item :label="t('invoice.reimburseNoLabel')" required :error="err.no">
        <el-input
          v-model="form.reimbursementNo"
          :placeholder="t('invoice.reimburseNoPlaceholder')"
          clearable
          maxlength="64"
        />
      </el-form-item>

      <el-form-item :label="t('invoice.reimburserLabel')" required :error="err.reimburser">
        <el-tree-select
          v-model="form.reimburser"
          :data="tree"
          :loading="loading"
          :default-expanded-keys="expandedKeys"
          :placeholder="t('orgTree.searchPlaceholder')"
          check-strictly
          filterable
          clearable
          style="width: 100%;"
        />
      </el-form-item>

      <el-form-item :label="t('invoice.reimburseDateLabel')">
        <el-date-picker
          v-model="form.reimburseDate"
          type="date"
          value-format="YYYY-MM-DD"
          format="YYYY-MM-DD"
          style="width: 100%;"
        />
      </el-form-item>

      <!--
        发起审批流程开关。关闭 = 确认即完成报销（历史行为）；
        打开 = 提交后进入「审批中」，审批通过才转「已报销」。
        无 invoice:reimburse:submit 权限的用户看不到开关，只能走关闭分支。
      -->
      <el-form-item v-if="canStartFlow" :label="t('invoice.withFlowLabel')">
        <el-switch v-model="withFlow" />
        <span class="with-flow-hint">
          {{ withFlow ? t('invoice.withFlowOnHint') : t('invoice.withFlowOffHint') }}
        </span>
      </el-form-item>

      <!--
        动态「报销申请单」：表单 code 由字典 invoice_reimburse_form 绑定，
        管理员在表单设计器里改布局后这里下次打开即生效，无需发版。
        仅在开关打开时才加载 —— 关闭分支用不到这张表，不该白拉一次。
        加载失败降级为「只填三件套也能提交」，不阻断报销主流程。
      -->
      <template v-if="withFlow && formFields.length">
        <el-divider>{{ formMeta?.name || t('invoice.reimburseFormTitle') }}</el-divider>
        <div v-loading="formLoading" class="reimburse-form-wrap">
          <FormRenderer
            :fields="formFields"
            :model-value="formData"
            label-width="100px"
            @update:model-value="(v: Record<string, any>) => (formData = v)"
          />
        </div>
      </template>
      <el-alert
        v-else-if="withFlow && formLoadFailed"
        type="warning"
        :closable="false"
        show-icon
        class="reimburse-form-alert"
      >
        {{ t('invoice.reimburseFormUnavailable') }}
      </el-alert>
    </el-form>

    <template #footer>
      <el-button :disabled="submitting" @click="emit('update:modelValue', false)">
        {{ t('common.cancel') }}
      </el-button>
      <el-button type="primary" :loading="submitting" @click="confirm">
        {{ t('invoice.reimburseConfirm') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 报销信息弹窗 —— 上传识别成功后点「报销」弹出。
 *
 * <p>两段结构：
 * <ol>
 *   <li><b>固定段（代码写死）</b>：报销单号 / 报销人 / 报销日期。
 *       报销单号决定 storageKey 目录 {@code fapiao/{单号}/}，放进表单设计器意味着
 *       管理员能把它删掉 → 直接破坏 commit；报销人要用组织树，而表单设计器的
 *       USER 类型是扁平下拉，会丢掉树。所以这三项不交给设计器。</li>
 *   <li><b>动态段（表单设计器）</b>：业务字段，布局可随时改。</li>
 * </ol>
 *
 * <p>只负责收集 + 校验，报销信息通过 confirm 事件交给父组件（Upload.vue）
 * 调 commit 接口 —— 弹窗本身不碰网络，便于复用。
 */
import { computed, nextTick, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import FormRenderer from '@/components/form/FormRenderer.vue';
import { useOrgTree, type OrgNode } from '@/composables/useOptionsCache';
import { formDesignApi, type FormDesignItem, type FormFieldSchema } from '@/api/formDesign';
import { useAuthStore } from '@/stores/auth';
import { useDict } from '@/stores/dict';
import { today } from '@/utils/time';

/** 报销弹窗绑定哪张表单：字典单条记录 value = sys_form_design.code */
const DICT_REIMBURSE_FORM = 'invoice_reimburse_form';
/** 字典缺失 / 读失败时的兜底表单 code —— 与 V57 seed 的 expense-apply 对应 */
const FALLBACK_FORM_CODE = 'expense-apply';

const props = defineProps<{
  modelValue: boolean;
  /** 本次报销的发票条数，仅用于标题文案 */
  count?: number;
  /** 报销人默认值 = 当前登录用户 id（组织树是异步的，可能晚于弹窗打开） */
  defaultReimburserId?: number;
  submitting?: boolean;
}>();

const emit = defineEmits<{
  'update:modelValue': [boolean];
  confirm: [payload: {
    verificationNo: string;
    verifier: string;
    verificationDate?: string;
    formData?: Record<string, any>;
    withFlow: boolean;
  }];
}>();

const { t } = useI18n();
const auth = useAuthStore();
const { get: getDictItems } = useDict();

/* ===================== 发起审批流开关 ===================== */

const withFlow = ref(false);
/** 无此权限的用户看不到开关，直接走「确认即完成报销」的历史路径 */
const canStartFlow = computed(() => auth.hasPerm('invoice:reimburse:submit'));

/* ===================== 固定段：报销单号 / 报销人 / 报销日期 ===================== */

/** 表单里存的是节点 value（`user-12`），提交时才映射成人名 —— 见 confirm() */
const form = reactive({ reimbursementNo: '', reimburser: '', reimburseDate: '' });
const err = reactive({ no: '', reimburser: '' });

const { tree, loading } = useOrgTree();

/**
 * 只展开顶层部门，下面的子部门 / 人员保持折叠。
 *
 * <p>为什么要 nextTick：ElTree 里 `defaultExpandedKeys` 的 watcher 注册顺序在 `data`
 * 之前（element-plus/es/components/tree/src/tree.vue_vue_type_script_lang.mjs:72 vs :75）。
 * 组织树是异步加载的，数据与展开键会在同一 tick 变化，于是 `setDefaultExpandedKeys` 先跑，
 * 此刻 store 里还是旧数据，`getNode(key)` 全部落空 → 键被静默丢弃；随后 `setData` 建树，
 * 而 `Node.initialize()` 只认 `defaultExpandAll`、不会回头补展开。所以键必须晚一个 tick 送进去。
 *
 * <p>也因此**不能**同时写 `default-expand-all`：它会在建树时把所有节点置为 expanded，
 * 而 `setDefaultExpandedKeys` 只会「加」展开、不会「收」展开，两者在同一实例上互相打架。
 */
const expandedKeys = ref<string[]>([]);
watch(
  tree,
  (nodes) => {
    nextTick(() => {
      expandedKeys.value = nodes.map((n) => n.value);
    });
  },
  { immediate: true },
);

/** value → 人员节点。树只加载一次，这里建索引供 confirm 反查人名 */
const userIndex = new Map<string, OrgNode>();

watch(
  tree,
  (nodes) => {
    userIndex.clear();
    const walk = (list: OrgNode[]) => {
      for (const n of list) {
        if (n.kind === 'user') userIndex.set(n.value, n);
        if (n.children) walk(n.children);
      }
    };
    walk(nodes);
    applyDefaultReimburser();
  },
  { immediate: true, deep: true },
);

/** 默认选中当前登录用户。用户手动选过就不再覆盖。 */
function applyDefaultReimburser() {
  if (!props.modelValue) return;
  if (form.reimburser) return;
  const id = props.defaultReimburserId;
  if (!id) return;
  const v = `user-${id}`;
  if (userIndex.has(v)) form.reimburser = v;
}

/* ===================== 动态段：报销申请单 ===================== */

const formFields = ref<FormFieldSchema[]>([]);
const formMeta = ref<FormDesignItem | null>(null);
const formLoading = ref(false);
const formLoadFailed = ref(false);
let formData = ref<Record<string, any>>({});

/** 读字典拿表单 code；字典没配 / 没这类型就退回兜底 code */
function resolveFormCode(): string {
  const items = getDictItems(DICT_REIMBURSE_FORM);
  const code = items.find((i) => i.code === 'default')?.value || items[0]?.value;
  return (code && String(code).trim()) || FALLBACK_FORM_CODE;
}

/** 拉表单 schema。失败只告警不阻断 —— 报销主流程不该被一张表单拖住。 */
async function loadReimburseForm() {
  if (formLoading.value) return;
  formLoading.value = true;
  formLoadFailed.value = false;
  try {
    const meta = await formDesignApi.detailByCode(resolveFormCode());
    formMeta.value = meta;
    formFields.value = meta.fields || [];
  } catch (e) {
    console.warn('load reimburse form failed', e);
    formMeta.value = null;
    formFields.value = [];
    formLoadFailed.value = true;
  } finally {
    formLoading.value = false;
  }
}

// 开关打开时才拉表单：关闭分支根本用不到这张表，不该白发一次请求。
// 同一次弹窗内来回切开关不重复拉。
watch(withFlow, (on) => {
  if (on && !formFields.value.length && !formLoadFailed.value) loadReimburseForm();
});

/* ===================== 打开 / 重置 ===================== */

/** 每次打开重置：单号必清空（同一单号重复入库会撞存储路径），日期默认当天 */
watch(
  () => props.modelValue,
  (open) => {
    if (!open) return;
    form.reimbursementNo = '';
    form.reimburser = '';
    form.reimburseDate = today();
    formData.value = {};
    err.no = '';
    err.reimburser = '';
    withFlow.value = false;
    formFields.value = [];
    formMeta.value = null;
    formLoadFailed.value = false;
    applyDefaultReimburser();
  },
  { immediate: true },
);

function confirm() {
  const no = form.reimbursementNo.trim();
  const picked = form.reimburser ? userIndex.get(form.reimburser) : undefined;
  err.no = no ? '' : t('invoice.reimburseRequired');
  if (!form.reimburser) {
    err.reimburser = t('invoice.reimburseRequired');
  } else if (!picked?.userName) {
    // 组织架构刷新后原选中的人可能已不在树里 —— 不能拿 node value 当人名提交
    err.reimburser = t('invoice.reimburserStale');
  } else {
    err.reimburser = '';
  }
  if (err.no || err.reimburser) return;

  // 申请单字段全空就不提交（后端也会把空对象归一成 null 列）
  const data = formData.value;
  const hasFormData = Object.values(data).some((v) => {
    if (v === null || v === undefined || v === '') return false;
    if (Array.isArray(v)) return v.length > 0;
    if (typeof v === 'object') return Object.keys(v as object).length > 0;
    return true;
  });

  const flow = withFlow.value && canStartFlow.value;

  emit('confirm', {
    verificationNo: no,
    // 存人名而不是 user id：verifier 列在列表里按文本展示与筛选，
    // 历史记录也因此保留「当时的人名」，人员改名不影响旧单据。
    verifier: picked!.userName!,
    verificationDate: form.reimburseDate || undefined,
    // 开关关闭时不提交申请单数据 —— 那一段本来就看不见，提交了就是脏数据
    formData: flow && hasFormData ? data : undefined,
    withFlow: flow,
  });
}
</script>

<style scoped>
.reimburse-form-wrap {
  min-height: 40px;
  padding: 0 2px;
}
.with-flow-hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--color-text-muted);
}
.reimburse-form-alert {
  margin-top: 8px;
}
:deep(.reimburse-dialog) {
  max-width: 92vw;
}
</style>