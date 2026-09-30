<template>
  <div class="page-container fade-in-up todo-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('todo.title') }}</h1>
        <p class="page-desc muted">{{ t('todo.desc') }}</p>
      </div>
      <el-button
        v-if="auth.hasPerm('todo:task:create')"
        type="primary"
        :icon="Plus"
        @click="openCreate"
      >
        {{ t('todo.create') }}
      </el-button>
    </div>

    <div class="todo-table-wrap">
      <!-- 工具栏：筛选 + 搜索 -->
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          :placeholder="t('todo.searchPlaceholder')"
          style="width: 240px;"
          clearable
          :prefix-icon="Search"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-select v-model="query.status" :placeholder="t('todo.fields.status')" clearable style="width: 120px;" @change="reload">
          <el-option :label="t('todo.status.pending')" value="PENDING" />
          <el-option :label="t('todo.status.completed')" value="COMPLETED" />
        </el-select>
        <el-select v-model="query.priority" :placeholder="t('todo.fields.priority')" clearable style="width: 120px;" @change="reload">
          <el-option :label="t('todo.priority.high')" value="HIGH" />
          <el-option :label="t('todo.priority.medium')" value="MEDIUM" />
          <el-option :label="t('todo.priority.low')" value="LOW" />
          <el-option :label="t('todo.priority.none')" value="NONE" />
        </el-select>
        <el-button type="primary" @click="reload">{{ t('common.search') }}</el-button>
        <span class="toolbar-spacer" />
        <span v-if="adminViewAll" class="admin-badge" :title="t('todo.fields.assignee')">
          <el-icon><View /></el-icon>{{ t('todo.fields.assignee') }}: {{ t('todo.adminViewAllLabel') }}
        </span>
        <span class="muted small">{{ t('todo.totalCount', { n: total }) }}</span>
      </div>

      <el-table
        :data="rows"
        v-loading="loading"
        border
        stripe
        size="small"
        :empty-text="t('common.empty')"
        :row-class-name="rowClass"
      >
        <!-- 完成 checkbox（inline） -->
        <el-table-column :label="t('todo.fields.done')" width="56" align="center">
          <template #default="{ row }">
            <div class="done-cell" :class="{ 'is-child': (row as TodoTask).parentId }">
              <el-checkbox
                :model-value="(row as TodoTask).status === 'COMPLETED'"
                :disabled="(row as TodoTask).status === 'COMPLETED' || !auth.hasPerm('todo:task:update')"
                @change="(v) => toggleDone(row as TodoTask, v as boolean)"
              />
            </div>
          </template>
        </el-table-column>

        <!-- 标题 + 标签 + 父任务链 + 优先级色条 -->
        <el-table-column :label="t('todo.fields.title')" min-width="280">
          <template #default="{ row }">
            <div class="todo-title-cell" :class="{ 'is-child': (row as TodoTask).parentId }">
              <span v-if="(row as TodoTask).parentId" class="child-connector" aria-hidden="true">└─</span>
              <span class="prio-bar" :class="`prio-${(row as TodoTask).priority}`" />
              <div class="todo-title-body">
                <div class="todo-title-row">
                  <span
                    class="todo-title"
                    :class="{ done: (row as TodoTask).status === 'COMPLETED' }"
                  >{{ row.title }}</span>
                  <span v-if="(row as TodoTask).recurrence !== 'NONE'" class="recurrence-badge">
                    <el-icon><Refresh /></el-icon>{{ recurrenceBadgeText(row as TodoTask) }}
                  </span>
                </div>
                <div v-if="(row as TodoTask).tagNames?.length" class="todo-meta-row">
                  <el-tag
                    v-for="tn in (row as TodoTask).tagNames"
                    :key="tn"
                    size="small"
                    effect="plain"
                    round
                  >{{ tn }}</el-tag>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>

        <!-- 执行人（仅 adminViewAll 模式） -->
        <el-table-column
          v-if="adminViewAll"
          :label="t('todo.fields.assignee')"
          width="120"
        >
          <template #default="{ row }">
            <div class="assignee-cell">
              <span class="assignee-name">{{ (row as TodoTask).assigneeName || `#${(row as TodoTask).assigneeId}` }}</span>
              <span v-if="(row as TodoTask).assigneeId === auth.user?.id" class="self-badge">{{ t('todo.selfBadge') }}</span>
            </div>
          </template>
        </el-table-column>

        <!-- 截止时间 -->
        <el-table-column :label="t('todo.fields.dueAt')" width="160">
          <template #default="{ row }">
            <span v-if="!(row as TodoTask).dueAt" class="muted">—</span>
            <span v-else class="due-cell" :class="dueClass(row as TodoTask)">
              {{ formatDue((row as TodoTask)) }}
            </span>
          </template>
        </el-table-column>

        <!-- 优先级 -->
        <el-table-column :label="t('todo.fields.priority')" width="100" align="center">
          <template #default="{ row }">
            <span class="prio-tag" :class="`prio-tag-${(row as TodoTask).priority}`">
              {{ t(`todo.priority.${((row as TodoTask).priority || 'NONE').toLowerCase()}`) }}
            </span>
          </template>
        </el-table-column>

        <!-- 状态 -->
        <el-table-column :label="t('todo.fields.status')" width="100" align="center">
          <template #default="{ row }">
            <el-tag
              :type="(row as TodoTask).status === 'COMPLETED' ? 'success' : 'info'"
              effect="light"
              size="small"
            >{{ t(`todo.status.${((row as TodoTask).status || 'pending').toLowerCase()}`) }}</el-tag>
          </template>
        </el-table-column>

        <!-- 创建时间 -->
        <el-table-column :label="t('todo.fields.createdAt')" width="170">
          <template #default="{ row }">
            <span class="muted small">{{ (row as TodoTask).createdAt }}</span>
          </template>
        </el-table-column>

        <!-- 操作列：编辑图标 + ··· 收纳删除 -->
        <el-table-column :label="t('common.actions')" width="120" align="center" fixed="right">
          <template #default="{ row }">
            <el-tooltip v-if="auth.hasPerm('todo:task:update')" :content="t('common.edit')" placement="top">
              <el-button size="small" link :icon="Edit" @click="openEdit(row as TodoTask)" />
            </el-tooltip>
            <el-dropdown
              v-if="auth.hasPerm('todo:task:delete')"
              trigger="click"
              @command="(cmd: string) => onCmd(cmd, row as TodoTask)"
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
    </div>

    <!--
      创建/编辑弹窗：
      label-width 100px 与系统其他页面对齐。
      2 个 tab：基本 / 附件。
    -->
    <el-dialog
      v-model="dialog.visible"
      :title="dialog.title"
      width="640px"
      append-to-body
      destroy-on-close
      align-center
    >
      <el-tabs v-model="dialog.tab" class="todo-tabs">
        <!-- ===== 基本 ===== -->
        <el-tab-pane name="basic">
          <template #label>{{ t('todo.tabs.basic') }}</template>
          <el-form
            :model="form"
            :rules="rules"
            ref="formRef"
            label-width="100px"
            class="basic-form"
          >
            <el-form-item :label="t('todo.fields.title')" prop="title">
              <el-input v-model="form.title" maxlength="200" show-word-limit />
            </el-form-item>
            <el-form-item :label="t('todo.fields.description')">
              <el-input v-model="form.description" type="textarea" :rows="3" />
            </el-form-item>
            <el-form-item :label="t('todo.fields.assignee')" v-if="canShowAssigneePicker">
              <el-select
                v-model="form.assigneeId"
                :placeholder="t('todo.assigneePlaceholder')"
                style="width: 100%;"
                clearable filterable
              >
                <el-option
                  v-for="u in assigneeOptions"
                  :key="u.id"
                  :label="u.displayName || u.username"
                  :value="u.id"
                />
              </el-select>
              <div class="form-hint muted small">
                {{ canReassign ? t('todo.assigneePlaceholder') : t('todo.assigneeForbidden') }}
              </div>
            </el-form-item>
            <el-form-item :label="t('todo.fields.priority')">
              <el-select v-model="form.priority" style="width: 100%;">
                <el-option :label="t('todo.priority.high')" value="HIGH" />
                <el-option :label="t('todo.priority.medium')" value="MEDIUM" />
                <el-option :label="t('todo.priority.low')" value="LOW" />
                <el-option :label="t('todo.priority.none')" value="NONE" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('todo.fields.recurrence')">
              <el-select v-model="form.recurrence" style="width: 100%;">
                <el-option :label="t('todo.recurrence.none')" value="NONE" />
                <el-option :label="t('todo.recurrence.daily')" value="DAILY" />
                <el-option :label="t('todo.recurrence.weekly')" value="WEEKLY" />
                <el-option :label="t('todo.recurrence.monthly')" value="MONTHLY" />
                <el-option :label="t('todo.recurrence.yearly')" value="YEARLY" />
              </el-select>
            </el-form-item>
            <!-- WEEKLY: 周几 -->
            <el-form-item v-if="form.recurrence === 'WEEKLY'" :label="t('todo.fields.recurrenceDow')">
              <el-select v-model="form.recurrenceDow" style="width: 100%;">
                <el-option v-for="d in DOW_OPTIONS" :key="d"
                  :label="t(`todo.recurrence.dow${d}`)" :value="d" />
              </el-select>
            </el-form-item>
            <!-- MONTHLY: 几号（含「最后一天」） -->
            <el-form-item v-if="form.recurrence === 'MONTHLY'" :label="t('todo.fields.recurrenceDom')">
              <el-select v-model="form.recurrenceDom" style="width: 100%;">
                <el-option v-for="d in DOM_OPTIONS" :key="d"
                  :label="`${d} ${t('todo.recurrence.dayUnit')}`" :value="d" />
                <el-option :label="t('todo.recurrence.lastDay')" :value="0" />
              </el-select>
            </el-form-item>
            <!-- YEARLY: 月 + 日 -->
            <el-form-item v-if="form.recurrence === 'YEARLY'" :label="t('todo.fields.recurrenceDate')">
              <el-select v-model="form.recurrenceMonth" style="width: 48%;" :placeholder="t('todo.fields.recurrenceMonth')">
                <el-option v-for="m in MONTH_OPTIONS" :key="m" :label="m" :value="m" />
              </el-select>
              <el-select v-model="form.recurrenceDom" style="width: 48%; margin-left: 4%;"
                :placeholder="t('todo.fields.recurrenceDom')">
                <el-option v-for="d in DOM_OPTIONS" :key="d"
                  :label="`${d} ${t('todo.recurrence.dayUnit')}`" :value="d" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('todo.fields.dueAt')">
              <el-date-picker
                v-model="form.dueAt"
                type="datetime"
                :placeholder="t('todo.duePlaceholder')"
                value-format="YYYY-MM-DD HH:mm:ss"
                format="YYYY-MM-DD HH:mm"
                style="width: 100%;"
                clearable
              />
            </el-form-item>
            <el-form-item :label="t('todo.fields.parent')">
              <el-select v-model="form.parentId" :placeholder="t('todo.parentPlaceholder')" style="width: 100%;" clearable filterable>
                <el-option
                  v-for="p in parentCandidates"
                  :key="p.id"
                  :label="`#${p.id} ${p.title}`"
                  :value="p.id"
                />
              </el-select>
              <div class="form-hint muted small">{{ t('todo.parentHint') }}</div>
            </el-form-item>
            <el-form-item :label="t('todo.fields.tags')">
              <el-select
                v-model="form.tagNames"
                multiple
                filterable
                allow-create
                default-first-option
                :placeholder="t('todo.tagsPlaceholder')"
                style="width: 100%;"
              >
                <el-option v-for="t in allTags" :key="t.id" :label="t.name" :value="t.name" />
              </el-select>
              <div class="form-hint muted small">{{ t('todo.tagsHint') }}</div>
            </el-form-item>
            <el-form-item v-if="dialog.mode === 'edit'" :label="t('todo.fields.status')">
              <el-radio-group v-model="form.status">
                <el-radio-button value="PENDING">{{ t('todo.status.pending') }}</el-radio-button>
                <el-radio-button value="COMPLETED">{{ t('todo.status.completed') }}</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item v-if="dialog.mode === 'edit'" :label="t('todo.fields.createdBy')">
              <span class="muted">{{ currentTaskCreatedByName || `#${currentTaskCreatedBy || '-'}` }}</span>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- ===== 附件 ===== -->
        <el-tab-pane name="attachments">
          <template #label>
            <span>
              <el-icon style="vertical-align: -2px;"><Paperclip /></el-icon>
              {{ t('todo.tabs.attachments') }}
              <el-badge
                v-if="attachments.length > 0"
                :value="attachments.length"
                :max="99"
                class="att-badge"
              />
            </span>
          </template>
          <div v-if="dialog.mode === 'create'" class="att-disabled muted">
            <el-icon><InfoFilled /></el-icon>
            {{ t('todo.attachments.empty') }} — {{ t('common.save') }} {{ t('common.actions') }}
          </div>
          <template v-else>
            <!-- 上传 dropzone -->
            <el-upload
              v-if="auth.hasPerm('todo:attachment:upload')"
              drag multiple
              :show-file-list="false"
              :http-request="uploadCustom"
              :before-upload="beforeUpload"
              class="att-uploader"
            >
              <el-icon class="att-upload-icon"><UploadFilled /></el-icon>
              <div class="att-upload-title">{{ t('todo.attachments.dropHint') }}</div>
              <div class="att-upload-hint muted small">{{ t('todo.attachments.uploadHint') }}</div>
            </el-upload>

            <!-- 已有附件列表 -->
            <div v-if="attachments.length > 0" class="att-list">
              <div
                v-for="a in attachments"
                :key="a.id"
                class="att-row"
              >
                <el-icon class="att-icon" :class="iconClass(a.mimeType, a.originalFilename)">
                  <component :is="iconComponent(a.mimeType, a.originalFilename)" />
                </el-icon>
                <div class="att-body">
                  <div class="att-name" :title="a.originalFilename">{{ a.originalFilename }}</div>
                  <div class="att-meta muted small">
                    <span>{{ a.sizeLabel }}</span>
                    <span class="att-dot">·</span>
                    <span>{{ t('todo.attachments.uploadedBy') }}: {{ a.uploaderName || `#${a.uploaderId}` }}</span>
                    <span class="att-dot">·</span>
                    <span class="mono">{{ a.createdAt }}</span>
                  </div>
                </div>
                <div class="att-actions">
                  <el-tooltip :content="t('todo.attachments.download')" placement="top">
                    <el-button size="small" link :icon="Download" @click="downloadAttachment(a)" />
                  </el-tooltip>
                  <el-tooltip
                    v-if="auth.hasPerm('todo:attachment:delete')"
                    :content="t('todo.attachments.delete')"
                    placement="top"
                  >
                    <el-button
                      size="small"
                      link
                      :icon="Delete"
                      class="danger-text"
                      @click="deleteAttachment(a)"
                    />
                  </el-tooltip>
                </div>
              </div>
            </div>
            <div v-else class="att-empty muted">
              <el-icon><Paperclip /></el-icon>
              {{ t('todo.attachments.empty') }}
            </div>
          </template>
        </el-tab-pane>
      </el-tabs>

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
import { ElMessage, ElMessageBox, type FormInstance, type FormRules, type UploadRequestOptions } from 'element-plus';
import {
  Plus, Search, Edit, Delete, MoreFilled, Refresh,
  UploadFilled, Download, Paperclip, InfoFilled, View,
  Document, Picture, VideoCamera, Headset, Files
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import {
  todoApi, todoTagApi, todoAttachmentApi,
  type TodoTask, type TodoTag, type TodoParentCandidate,
  type TodoAssigneeOption, type TodoAttachment
} from '@/api/todo';

const { t } = useI18n();
const auth = useAuthStore();

const rows = ref<TodoTask[]>([]);
const total = ref(0);
const loading = ref(false);
const adminViewAll = ref(false);
const query = reactive({ page: 1, size: 20, keyword: '', status: '', priority: '' });

const allTags = ref<TodoTag[]>([]);
const parentCandidates = ref<TodoParentCandidate[]>([]);
const assigneeOptions = ref<TodoAssigneeOption[]>([]);
const attachments = ref<TodoAttachment[]>([]);

const dialog = reactive({
  visible: false,
  mode: 'create' as 'create' | 'edit',
  title: '',
  loading: false,
  editingId: 0 as number,
  tab: 'basic' as 'basic' | 'attachments',
});

const form = reactive({
  title: '',
  description: '',
  priority: 'NONE' as TodoTask['priority'],
  status: 'PENDING' as TodoTask['status'],
  recurrence: 'NONE' as TodoTask['recurrence'],
  recurrenceDow: null as number | null,
  recurrenceDom: null as number | null,
  recurrenceMonth: null as number | null,
  dueAt: null as string | null,
  parentId: null as number | null,
  tagNames: [] as string[],
  /** 仅 admin / 创建者可指定；其他情况可编辑但实际被 service 忽略（保持当前用户）。 */
  assigneeId: null as number | null,
});

const MONTH_OPTIONS = Array.from({ length: 12 }, (_, i) => i + 1);
const DOM_OPTIONS = Array.from({ length: 31 }, (_, i) => i + 1);
const DOW_OPTIONS = Array.from({ length: 7 }, (_, i) => i + 1);

/** 把 recurrence 配置 + 国际化拼成徽章文案（不含类型前缀，由调用方拼「重复」）。 */
function recurrenceBadgeText(row: TodoTask): string {
  switch (row.recurrence) {
    case 'WEEKLY': {
      const dow = row.recurrenceDow;
      if (!dow) return t('todo.recurrence.weekly');
      return t('todo.recurrence.weeklyDow', { dow: t(`todo.recurrence.dow${dow}`) });
    }
    case 'MONTHLY': {
      const dom = row.recurrenceDom;
      if (dom === 0 || dom == null) return t('todo.recurrence.lastDay');
      return t('todo.recurrence.monthlyDay', { n: dom });
    }
    case 'YEARLY': {
      const m = row.recurrenceMonth;
      const d = row.recurrenceDom;
      if (!m || !d) return t('todo.recurrence.yearly');
      return t('todo.recurrence.yearlyMd', { m, d });
    }
    default:
      return t(`todo.recurrence.${(row.recurrence || 'NONE').toLowerCase()}`);
  }
}

const currentTaskCreatedBy = ref<number | null>(null);
const currentTaskCreatedByName = ref<string>('');

const formRef = ref<FormInstance>();
const rules = computed<FormRules>(() => ({
  title: [{ required: true, message: t('common.required') }],
}));

/** admin 全员模式 + 当前用户可改自己 → 显示 assignee picker */
const canShowAssigneePicker = computed(() => {
  if (dialog.mode === 'create') {
    return auth.hasPerm('todo:task:assign') || adminViewAll.value;
  }
  // 编辑模式：admin 或当前任务创建者可改
  return auth.hasPerm('todo:task:assign') || (currentTaskCreatedBy.value === auth.user?.id);
});

/** picker 真正能改 assignee（区分 admin / 创建者 / 仅查看） */
const canReassign = computed(() => canShowAssigneePicker.value);

async function reload() {
  loading.value = true;
  try {
    const r = await todoApi.list({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      priority: query.priority || undefined,
    });
    rows.value = sortHierarchical(r.items);
    total.value = r.total;
    adminViewAll.value = r.adminViewAll === true;
  } finally {
    loading.value = false;
  }
}

/**
 * 把扁平列表重排成「父级在前 → 子任务紧随缩进」的两级结构。
 * 每组内仍按状态/dueAt/id 排序（与原页面一致），保证逾期父级优先、其子任务紧随。
 */
function sortHierarchical(tasks: TodoTask[]): TodoTask[] {
  const byParent = new Map<number | null, TodoTask[]>();
  for (const t of tasks) {
    const key = t.parentId ?? null;
    if (!byParent.has(key)) byParent.set(key, []);
    byParent.get(key)!.push(t);
  }
  const sortGroup = (arr: TodoTask[]) =>
    [...arr].sort((a, b) => {
      const sa = a.status === 'COMPLETED' ? 1 : 0;
      const sb = b.status === 'COMPLETED' ? 1 : 0;
      if (sa !== sb) return sa - sb;
      const da = a.dueAt ? new Date(a.dueAt).getTime() : Number.POSITIVE_INFINITY;
      const db = b.dueAt ? new Date(b.dueAt).getTime() : Number.POSITIVE_INFINITY;
      if (da !== db) return da - db;
      return b.id - a.id;
    });
  const out: TodoTask[] = [];
  const visit = (parentKey: number | null) => {
    const children = sortGroup(byParent.get(parentKey) || []);
    for (const c of children) {
      out.push(c);
      visit(c.id);
    }
  };
  visit(null);
  return out;
}

async function loadTags() {
  allTags.value = await todoTagApi.list();
}

async function loadParentCandidates(excludeId?: number) {
  parentCandidates.value = await todoApi.parentCandidates(excludeId);
}

async function loadAssignees() {
  assigneeOptions.value = await todoApi.assignees();
}

async function loadAttachments(taskId: number) {
  attachments.value = await todoAttachmentApi.list(taskId);
}

function openCreate() {
  dialog.mode = 'create';
  dialog.title = t('todo.createTitle');
  dialog.editingId = 0;
  dialog.tab = 'basic';
  currentTaskCreatedBy.value = null;
  currentTaskCreatedByName.value = '';
  attachments.value = [];
  Object.assign(form, {
    title: '',
    description: '',
    priority: 'NONE',
    status: 'PENDING',
    recurrence: 'NONE',
    recurrenceDow: null,
    recurrenceDom: null,
    recurrenceMonth: null,
    dueAt: null,
    parentId: null,
    tagNames: [],
    assigneeId: null,
  });
  dialog.visible = true;
  loadAssignees();
}

function openEdit(row: TodoTask) {
  dialog.mode = 'edit';
  dialog.title = t('todo.editTitle', { name: row.title });
  dialog.editingId = row.id;
  dialog.tab = 'basic';
  currentTaskCreatedBy.value = row.createdBy ?? null;
  currentTaskCreatedByName.value = row.createdByName || '';
  Object.assign(form, {
    title: row.title,
    description: row.description || '',
    priority: row.priority,
    status: row.status,
    recurrence: row.recurrence,
    recurrenceDow: row.recurrenceDow ?? null,
    recurrenceDom: row.recurrenceDom ?? null,
    recurrenceMonth: row.recurrenceMonth ?? null,
    dueAt: row.dueAt || null,
    parentId: row.parentId ?? null,
    tagNames: [...(row.tagNames || [])],
    assigneeId: row.assigneeId,
  });
  dialog.visible = true;
  loadAssignees();
  loadAttachments(row.id);
}

async function save() {
  if (!formRef.value) return;
  await formRef.value.validate(async (valid) => {
    if (!valid) return;
    // 循环配置完整性校验（前端提示，避免无意义往返）
    if (form.recurrence === 'WEEKLY' && (form.recurrenceDow == null)) {
      ElMessage.warning(t('todo.recurrence.required'));
      return;
    }
    if (form.recurrence === 'MONTHLY' && (form.recurrenceDom == null)) {
      ElMessage.warning(t('todo.recurrence.required'));
      return;
    }
    if (form.recurrence === 'YEARLY' && (form.recurrenceMonth == null || form.recurrenceDom == null)) {
      ElMessage.warning(t('todo.recurrence.required'));
      return;
    }
    dialog.loading = true;
    try {
      const payload: Record<string, unknown> = {
        title: form.title.trim(),
        description: form.description || undefined,
        priority: form.priority,
        status: form.status,
        recurrence: form.recurrence,
        recurrenceDow: form.recurrenceDow,
        recurrenceDom: form.recurrenceDom,
        recurrenceMonth: form.recurrenceMonth,
        dueAt: form.dueAt,
        parentId: form.parentId,
        tagNames: form.tagNames,
      };
      if (canReassign.value) {
        payload.assigneeId = form.assigneeId;
      }
      if (dialog.mode === 'create') {
        const newId = await todoApi.create(payload as any);
        ElMessage.success(t('common.created'));
        dialog.visible = false;
        reload();
        loadTags();
        // 创建后跳到编辑模式以便用户上传附件
        if (newId) {
          // 简化：不做二次打开，提示用户去列表再编辑
        }
      } else {
        await todoApi.update(dialog.editingId, payload as any);
        ElMessage.success(t('common.updated'));
        dialog.visible = false;
        reload();
        loadTags();
      }
    } finally {
      dialog.loading = false;
    }
  });
}

async function toggleDone(row: TodoTask, done: boolean) {
  if (!done) return;
  try {
    const nextId = await todoApi.complete(row.id);
    if (nextId) {
      ElMessage.success(t('todo.recurrenceSpawned'));
    } else {
      ElMessage.success(t('todo.completed'));
    }
    reload();
  } catch {
    /* 接口已在拦截器弹错 */
  }
}

async function remove(row: TodoTask) {
  await todoApi.remove(row.id);
  ElMessage.success(t('common.deleted'));
  reload();
  loadTags();
}

async function onCmd(cmd: string, row: TodoTask) {
  if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(
        t('todo.confirmDelete', { name: row.title }),
        t('common.confirmDelete'),
        { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') }
      );
      await remove(row);
    } catch { /* user cancelled */ }
  }
}

// ===== 附件 =====

function beforeUpload(file: File): boolean {
  if (file.size > 20 * 1024 * 1024) {
    ElMessage.error(t('todo.attachments.overSize'));
    return false;
  }
  return true;
}

async function uploadCustom(opts: UploadRequestOptions) {
  const file = opts.file as File;
  try {
    const result = await todoAttachmentApi.upload(dialog.editingId, file);
    attachments.value.unshift(result);
    ElMessage.success(`${t('todo.attachments.uploadSuccess')} · ${file.name}`);
    opts.onSuccess?.(result);
  } catch (e: any) {
    ElMessage.error(t('todo.attachments.uploadError', { msg: e?.message || String(e) }));
    opts.onError?.(e);
  }
}

async function downloadAttachment(a: TodoAttachment) {
  try {
    await todoAttachmentApi.download(a.id, a.originalFilename);
  } catch (e: any) {
    ElMessage.error(t('todo.attachments.uploadError', { msg: e?.message || String(e) }));
  }
}

async function deleteAttachment(a: TodoAttachment) {
  try {
    await ElMessageBox.confirm(
      t('todo.attachments.confirmDelete', { name: a.originalFilename }),
      t('common.confirmDelete'),
      { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') }
    );
    await todoAttachmentApi.remove(a.id);
    attachments.value = attachments.value.filter((x) => x.id !== a.id);
    ElMessage.success(t('common.deleted'));
  } catch { /* cancelled */ }
}

function iconComponent(mime: string | undefined, name: string) {
  if (mime?.startsWith('image/')) return Picture;
  if (mime?.startsWith('video/')) return VideoCamera;
  if (mime?.startsWith('audio/')) return Headset;
  const lower = (name || '').toLowerCase();
  if (/\.(xlsx?|csv)$/.test(lower)) return Files;
  if (/\.(pdf|docx?|txt|md|json|xml)$/.test(lower)) return Document;
  return Document;
}

function iconClass(mime: string | undefined, name: string) {
  if (mime?.startsWith('image/')) return 'att-icon-image';
  if (mime?.startsWith('video/')) return 'att-icon-video';
  if (mime?.startsWith('audio/')) return 'att-icon-audio';
  const lower = (name || '').toLowerCase();
  if (/\.(xlsx?|csv)$/.test(lower)) return 'att-icon-sheet';
  return 'att-icon-doc';
}

function dueClass(row: TodoTask) {
  if (!row.dueAt) return '';
  if (row.status === 'COMPLETED') return '';
  const d = new Date(row.dueAt).getTime();
  const now = Date.now();
  if (d < now) return 'due-overdue';
  const sameDay = new Date(row.dueAt).toDateString() === new Date().toDateString();
  if (sameDay) return 'due-today';
  return '';
}

function formatDue(row: TodoTask): string {
  if (!row.dueAt) return '';
  const d = new Date(row.dueAt);
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function rowClass({ row }: { row: TodoTask }) {
  return row.status === 'COMPLETED' ? 'todo-row-done' : '';
}

onMounted(async () => {
  await Promise.all([loadTags(), reload(), loadParentCandidates()]);
});
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 20px;
}
.page-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.01em;
}
.page-desc { margin: 4px 0 0; font-size: 13px; }

/* —— 双层吸顶 —— */
.todo-page .page-header {
  position: sticky;
  top: 0;
  z-index: 30;
  background: var(--color-bg);
  padding-top: 4px;
}
.todo-table-wrap { margin-top: 16px; overflow: visible; }
:deep(.todo-table-wrap th.el-table__cell) {
  position: sticky;
  top: 92px;
  z-index: 20;
  background: var(--color-bg) !important;
}

/* —— 工具栏 —— */
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  border: 1px solid var(--color-border-soft);
  border-radius: 10px 10px 0 0;
  background: var(--color-bg);
}
.toolbar-spacer { flex: 1; }
.admin-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  padding: 2px 8px;
  background: var(--color-primary-soft);
  color: var(--color-primary);
  border-radius: 4px;
  font-weight: 500;
}

/* —— 列表外框 —— */
:deep(.todo-table-wrap .el-table) {
  border-top-left-radius: 0;
  border-top-right-radius: 0;
  border: 1px solid var(--color-border-soft);
  border-top: none;
}

/* —— 标题 cell —— */
.todo-title-cell {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}
.todo-title-cell.is-child { padding-left: 24px; }
.child-connector {
  color: var(--color-text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  line-height: 22px;
  margin-right: 2px;
  margin-left: -16px;
  flex-shrink: 0;
  opacity: 0.55;
}
.done-cell.is-child { padding-left: 24px; }
.prio-bar {
  width: 3px;
  align-self: stretch;
  border-radius: 2px;
  flex-shrink: 0;
  background: var(--color-border-soft);
}
.prio-bar.prio-HIGH   { background: #ef4444; }
.prio-bar.prio-MEDIUM { background: #f59e0b; }
.prio-bar.prio-LOW    { background: #3b82f6; }
.prio-bar.prio-NONE   { background: var(--color-border-soft); }

.todo-title-body { flex: 1; min-width: 0; }
.todo-title-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.todo-title {
  font-weight: 500;
  color: var(--color-text);
  word-break: break-word;
}
.todo-title.done { text-decoration: line-through; color: var(--color-text-muted); }
.recurrence-badge {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--color-primary-soft);
  color: var(--color-primary);
  font-weight: 500;
}
.todo-meta-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  flex-wrap: wrap;
}

/* —— 执行人 cell —— */
.assignee-cell { display: inline-flex; align-items: center; gap: 6px; }
.assignee-name { color: var(--color-text); font-weight: 500; }
.self-badge {
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 3px;
  background: var(--color-primary-soft);
  color: var(--color-primary);
  font-weight: 600;
}

/* —— 优先级 chip —— */
.prio-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 500;
  border: 1px solid transparent;
}
.prio-tag-HIGH   { background: #fef2f2; color: #dc2626; border-color: #fecaca; }
.prio-tag-MEDIUM { background: #fffbeb; color: #d97706; border-color: #fed7aa; }
.prio-tag-LOW    { background: #eff6ff; color: #2563eb; border-color: #bfdbfe; }
.prio-tag-NONE   { background: var(--color-bg); color: var(--color-text-muted); border-color: var(--color-border-soft); }

/* —— 截止时间高亮 —— */
.due-cell { font-variant-numeric: tabular-nums; }
.due-overdue { color: var(--color-danger); font-weight: 600; }
.due-today   { color: #d97706; font-weight: 600; }

/* —— 行完成态弱化 —— */
:deep(.todo-row-done) { opacity: 0.65; }

/* —— 弹窗 —— */
.form-hint { margin-top: 4px; }

/* —— Tabs —— */
.todo-tabs { margin: -8px 0 0; }
.todo-tabs :deep(.el-tabs__nav-wrap::after) { background: transparent; }

/* —— 附件 dropzone —— */
.att-uploader { width: 100%; }
:deep(.att-uploader .el-upload) { width: 100%; display: block; }
:deep(.att-uploader .el-upload-dragger) {
  background: linear-gradient(135deg, #f0f4ff 0%, #f5f3ff 100%);
  border: 2px dashed #c7d2fe;
  border-radius: 12px;
  padding: 18px 24px;
  transition: all 0.2s ease;
}
:deep(.att-uploader .el-upload-dragger:hover) {
  border-color: var(--color-primary);
  background: linear-gradient(135deg, #eff6ff 0%, #eef2ff 100%);
}
.att-upload-icon {
  font-size: 32px;
  color: var(--color-primary);
  margin-bottom: 4px;
}
.att-upload-title { font-size: 14px; font-weight: 500; color: var(--color-text); }
.att-upload-hint { margin-top: 4px; }
.att-badge { margin-left: 4px; }

/* —— 附件列表 —— */
.att-list {
  margin-top: 12px;
  border: 1px solid var(--color-border-soft);
  border-radius: 10px;
  overflow: hidden;
}
.att-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--color-border-soft);
  transition: background 0.15s ease;
}
.att-row:last-child { border-bottom: none; }
.att-row:hover { background: var(--color-primary-soft); }
.att-icon {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  color: white;
  flex-shrink: 0;
}
.att-icon-image { background: linear-gradient(135deg, #34d399, #10b981); }
.att-icon-video { background: linear-gradient(135deg, #f472b6, #ec4899); }
.att-icon-audio { background: linear-gradient(135deg, #fbbf24, #f59e0b); }
.att-icon-sheet { background: linear-gradient(135deg, #60a5fa, #3b82f6); }
.att-icon-doc   { background: linear-gradient(135deg, #818cf8, #6366f1); }

.att-body { flex: 1; min-width: 0; }
.att-name {
  font-weight: 500;
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.att-meta { display: flex; align-items: center; gap: 4px; margin-top: 2px; flex-wrap: wrap; }
.att-dot { margin: 0 2px; }
.att-actions { display: flex; gap: 4px; flex-shrink: 0; }

.att-empty, .att-disabled {
  margin-top: 16px;
  padding: 24px;
  border: 1px dashed var(--color-border-soft);
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.basic-form { padding-top: 8px; }

.muted { color: var(--color-text-muted); }
.small { font-size: 12px; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
</style>