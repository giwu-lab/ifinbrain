<!--
  TableField —— 表格控件，两种模式：

  1) 展示模式（字段绑了 tableSource）：数据来自查询，填表人改不了。
     这是「用表单展示一份查询结果」的用法 —— 比如报销单里把预算明细查出来直接看。
     单元格渲染纯文本而不是禁用输入框：一屏灰色 disabled 输入框既难看又没法对齐。

  2) 输入模式（没绑查询）：明细子表，填表人自己增删行、填单元格。
     值形状：对象数组，列 key 即行对象的属性名。
       [ { name: '机票', amount: 1200 }, { name: '住宿', amount: 800 } ]
     单元格复用现有字段控件（cellRegistry），不重复实现 12 种输入：
     每格把列定义包装成一个临时 FormFieldSchema 传进去。
-->
<template>
  <div class="table-field" ref="hostEl">
    <p v-if="dynamic && error" class="table-field-error">{{ error }}</p>

    <el-table
      v-if="!display"
      :data="rows"
      size="small"
      border
      class="table-field-grid"
      empty-text=""
      flexible
      :scrollbar-always-on="inputScrollX"
    >
      <el-table-column
        type="index"
        width="48"
        align="center"
        :label="t('formDesigner.table.rowNo')"
      />
      <el-table-column
        v-for="col in columns"
        :key="col.key"
        :label="col.label || col.key"
        :width="col.width"
        :min-width="col.width ? undefined : 140"
        show-overflow-tooltip
      >
        <template #header>
          <span>{{ col.label || col.key }}</span>
          <span v-if="col.required" class="required-mark">*</span>
        </template>
        <template #default="{ $index }">
          <component
            :is="resolveCell(col.type)"
            :field="cellField(col)"
            :model-value="cellValue($index, col.key)"
            :readonly="readonly"
            :compact="col.type === 'FILE'"
            @update:model-value="(v: any) => setCell($index, col.key, v)"
          />
        </template>
      </el-table-column>
      <el-table-column
        v-if="!readonly"
        :label="t('common.actions')"
        width="104"
        align="center"
        fixed="right"
      >
        <template #default="{ $index }">
          <el-button
            link size="small" :icon="Top"
            :disabled="$index === 0"
            @click="moveRow($index, -1)"
          />
          <el-button
            link size="small" :icon="Bottom"
            :disabled="$index === rows.length - 1"
            @click="moveRow($index, 1)"
          />
          <el-button
            link size="small" :icon="Delete" class="danger-icon"
            @click="removeRow($index)"
          />
        </template>
      </el-table-column>
    </el-table>

    <!-- 展示模式：只读表格 -->
    <el-table
      v-else
      v-loading="loading"
      :data="displayRows"
      size="small"
      border
      stripe
      class="table-field-grid"
      :empty-text="t('formDesigner.table.displayEmpty')"
      flexible
      :scrollbar-always-on="displayScrollX"
    >
      <el-table-column
        v-for="col in displayColumns"
        :key="col.key"
        :prop="col.key"
        :label="col.label || col.key"
        :width="col.width"
        :min-width="col.width ? undefined : 140"
        :align="col.type === 'NUMBER' ? 'right' : 'left'"
        :formatter="cellFormatter(col)"
        show-overflow-tooltip
      />
      <template #empty>
        <span v-if="loading">{{ t('formDesigner.table.displayLoading') }}</span>
      </template>
    </el-table>

    <p v-if="!columns.length && !display" class="table-field-hint muted">
      {{ t('formDesigner.table.noColumns') }}
    </p>

    <div v-if="dynamic && !loading && !error" class="table-field-meta muted small">
      {{ t('formDesigner.table.displayRowCount', { n: queryRows.length }) }}
      <span v-if="clipped" class="table-field-truncated">
        {{ t('formDesigner.table.displayClipped', { shown: displayRows.length }) }}
      </span>
      <span v-else-if="truncated" class="table-field-truncated">
        {{ t('formDesigner.table.displayTruncated') }}
      </span>
    </div>

    <div v-if="!readonly && !display" class="table-field-actions">
      <el-button size="small" :icon="Plus" link type="primary" :disabled="!columns.length" @click="addRow">
        {{ t('formDesigner.table.addRow') }}
      </el-button>
      <span class="muted small">{{ t('formDesigner.table.rowCount', { n: rows.length }) }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { Plus, Delete, Top, Bottom } from '@element-plus/icons-vue';
import { resolveCell } from '../cellRegistry';
import { useTableData } from '@/composables/useTableData';
import { ACTIONS_COL_WIDTH, INDEX_COL_WIDTH, needsScrollX, tableMinWidth } from '@/utils/form/tableScroll';
import type { FormFieldSchema, FormFieldColumn } from '@/api/formDesign';

type TableRow = Record<string, any>;

const props = defineProps<{
  field: FormFieldSchema;
  modelValue?: TableRow[];
  readonly?: boolean;
  /** 把 tableSource.params 里的 {{}} 求值成实际值；不传则按字面量取 */
  resolveParam?: (raw: string) => string;
}>();
const emit = defineEmits<{ 'update:modelValue': [TableRow[]] }>();
const { t } = useI18n();

const columns = computed<FormFieldColumn[]>(() => props.field.columns || []);

/* ============ 横向滚动条 ============ */

/**
 * 容器实际宽度 —— 判定「要不要常驻横向滚动条」得跟它比。
 *
 * 用 ResizeObserver 而不是 onMounted 量一次：表单字段的格子宽度会随栅格列数、
 * 窗口缩放、侧栏展开而变，量一次很快就对不上了。
 */
const hostEl = ref<HTMLElement>();
const hostWidth = ref(0);
let ro: ResizeObserver | null = null;

onMounted(() => {
  if (!hostEl.value || typeof ResizeObserver === 'undefined') return;
  hostWidth.value = hostEl.value.clientWidth;
  ro = new ResizeObserver(() => {
    if (hostEl.value) hostWidth.value = hostEl.value.clientWidth;
  });
  ro.observe(hostEl.value);
});

onBeforeUnmount(() => {
  ro?.disconnect();
  ro = null;
});

/** 输入模式：左侧序号列 + 右侧操作列（只读时没有操作列） */
const inputScrollX = computed(() =>
  needsScrollX(
    tableMinWidth({
      columns: columns.value,
      indexColWidth: INDEX_COL_WIDTH,
      actionsColWidth: props.readonly ? 0 : ACTIONS_COL_WIDTH,
    }),
    hostWidth.value,
  ),
);

/** 展示模式：纯数据表，两侧都没有固定列 */
const displayScrollX = computed(() =>
  needsScrollX(tableMinWidth({ columns: displayColumns.value }), hostWidth.value),
);

// 切换模式时重新量一次：新表格的容器宽度和上一张不一定一样
watch(() => props.field.id, () => { hostWidth.value = hostEl.value?.clientWidth ?? 0; });

/* ============ 展示模式（绑了查询） ============ */

const fieldRef = computed(() => props.field);
const { rows: queryRows, queryColumns, loading, error, truncated, dynamic } =
  useTableData(fieldRef, props.resolveParam);

/** 绑了查询 = 展示模式：数据来自查询，填表人改不了 */
const display = computed(() => dynamic.value);

/**
 * 展示模式最多渲染多少行。
 *
 * el-table 不做虚拟滚动，DOM 节点数 ≈ 行数 × 列数。实测一条 609 行 × 14 列的查询
 * 就是 8500+ 个节点，而后端本身就允许最多 1 万行 —— 不设上限的话，
 * 绑个大查询的表单打开就会把页面拖死。
 */
const MAX_DISPLAY_ROWS = 200;

const displayRows = computed<TableRow[]>(() => queryRows.value.slice(0, MAX_DISPLAY_ROWS));
/** 数据被我们截断了（区别于后端自己截断的 truncated） */
const clipped = computed(() => queryRows.value.length > MAX_DISPLAY_ROWS);

/**
 * 展示用的列：优先用字段自己配的列（设计者调过标题/宽度/顺序的可能和查询不一样，
 * 那是刻意的，不该被查询覆盖）；字段压根没配列时用查询侧的兜底，
 * 这样即便从没同步过也能先把数据显出来。
 */
const displayColumns = computed<FormFieldColumn[]>(() =>
  columns.value.length ? columns.value : queryColumns.value,
);

/**
 * 单元格取值 → 显示文案。
 *
 * 下拉类列（select/radio/checkbox）存的是 value，显示的应该是人话 label，
 * 所以拿列上配的 options 反查一下；查不到就退回原值 —— 宁可显示原始值，
 * 也不显示空白让人以为这一格没数据。
 */
function cellFormatter(col: FormFieldColumn) {
  return (row: TableRow): string => {
    const v = row?.[col.key];
    if (v === null || v === undefined || v === '') return '';
    if (col.options?.length) {
      const hit = col.options.find((o) => o.value === v || o.value === String(v));
      if (hit) return hit.label;
    }
    return String(v);
  };
}

/* ============ 输入模式（没绑查询） ============ */

/**
 * 永远返回数组。
 *
 * <p>不能返回 null：流程条件里写 ${#items.?[...]} 时，null 会让 SpEL 内部抛 NPE，
 * 而 ConditionEvaluator.evaluate() 把异常吞掉一律返回 false —— 表现为「条件永远
 * 不成立」且页面上看不出任何错误。空数组走 SpEL 会正常得到 size() = 0。
 */
const rows = computed<TableRow[]>(() => (Array.isArray(props.modelValue) ? props.modelValue : []));

/** 列定义 → 临时字段 schema，让单元格控件按既有约定工作（options / placeholder / type） */
function cellField(col: FormFieldColumn): FormFieldSchema {
  return {
    id: `${props.field.id}__${col.key}`,
    type: col.type || 'TEXT',
    key: col.key,
    label: col.label || col.key,
    required: !!col.required,
    options: col.options,
  };
}

function cellValue(rowIndex: number, key: string) {
  return rows.value[rowIndex]?.[key];
}

function newRow(): TableRow {
  const r: TableRow = {};
  for (const c of columns.value) {
    if (!c.key) continue;
    // NUMBER 列用 null（el-input-number 的空值），其余用 '' 保持控件受控
    r[c.key] = c.type === 'NUMBER' ? null : '';
  }
  return r;
}

function setCell(rowIndex: number, key: string, value: any) {
  const next = rows.value.map((r, i) => (i === rowIndex ? { ...r, [key]: value } : r));
  emit('update:modelValue', next);
}

function addRow() {
  emit('update:modelValue', [...rows.value, newRow()]);
}

function removeRow(rowIndex: number) {
  emit('update:modelValue', rows.value.filter((_, i) => i !== rowIndex));
}

function moveRow(rowIndex: number, dir: -1 | 1) {
  const next = [...rows.value];
  const target = rowIndex + dir;
  if (target < 0 || target >= next.length) return;
  [next[rowIndex], next[target]] = [next[target], next[rowIndex]];
  emit('update:modelValue', next);
}
</script>

<style scoped>
.table-field { width: 100%; }
.table-field-grid { width: 100%; }
/* 单元格里的控件默认有 margin / 100% 宽，这里收紧成无边框贴合单元格 */
.table-field-grid :deep(.el-table__cell) { padding: 4px 6px; vertical-align: middle; }
.table-field-grid :deep(.el-form-item) { margin-bottom: 0; }
.required-mark { color: var(--color-danger, #f56c6c); margin-left: 2px; }

.table-field-hint {
  margin: 8px 0 0;
  font-size: 12px;
  text-align: center;
  padding: 10px 0;
  border: 1px dashed var(--color-border, #dcdfe6);
  border-radius: var(--radius-md, 4px);
}
.table-field-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
}
/* 展示模式的行数 / 截断提示 */
.table-field-meta {
  margin: 6px 0 0;
  display: flex;
  gap: 8px;
}
.table-field-truncated { color: var(--color-warning, #e6a23c); }
.table-field-error {
  margin: 0 0 6px;
  padding: 6px 8px;
  font-size: 12px;
  color: var(--color-danger, #f56c6c);
  background: var(--color-danger-light, #fef0f0);
  border-radius: var(--radius-md, 4px);
}
.small { font-size: 12px; }
.danger-icon :deep(.el-icon) { color: var(--color-danger, #f56c6c); }
</style>
