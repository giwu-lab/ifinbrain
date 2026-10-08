<!--
  TableField —— 表格（明细子表）控件

  值形状：对象数组，列 key 即行对象的属性名。
    [ { name: '机票', amount: 1200 }, { name: '住宿', amount: 800 } ]

  单元格复用现有字段控件（cellRegistry），不重复实现 12 种输入：
  每格把列定义包装成一个临时 FormFieldSchema 传进去。
-->
<template>
  <div class="table-field">
    <el-table
      :data="rows"
      size="small"
      border
      class="table-field-grid"
      empty-text=""
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

    <p v-if="!columns.length" class="table-field-hint muted">
      {{ t('formDesigner.table.noColumns') }}
    </p>

    <div v-if="!readonly" class="table-field-actions">
      <el-button size="small" :icon="Plus" link type="primary" :disabled="!columns.length" @click="addRow">
        {{ t('formDesigner.table.addRow') }}
      </el-button>
      <span class="muted small">{{ t('formDesigner.table.rowCount', { n: rows.length }) }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { Plus, Delete, Top, Bottom } from '@element-plus/icons-vue';
import { resolveCell } from '../cellRegistry';
import type { FormFieldSchema, FormFieldColumn } from '@/api/formDesign';

type TableRow = Record<string, any>;

const props = defineProps<{
  field: FormFieldSchema;
  modelValue?: TableRow[];
  readonly?: boolean;
}>();
const emit = defineEmits<{ 'update:modelValue': [TableRow[]] }>();
const { t } = useI18n();

const columns = computed<FormFieldColumn[]>(() => props.field.columns || []);

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
.small { font-size: 12px; }
.danger-icon :deep(.el-icon) { color: var(--color-danger, #f56c6c); }
</style>
