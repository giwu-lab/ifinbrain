<!--
  DynamicFormRenderer —— 按 fields schema 渲染完整表单。
  props:
    - fields: 字段列表
    - modelValue: 表单数据 Record<string, any>
    - readonly?: 是否只读
    - labelPosition?: 'top' | 'left'  (默认 'top')
    - labelWidth?: string             (默认 '100px'，与 AGENTS.md §7 一致)
  emit:
    - update:modelValue
    - submit: 用户按底栏提交按钮（若有 prop showSubmit:true）
-->
<template>
  <el-form
    ref="formRef"
    :model="formData"
    :rules="rules"
    :label-position="labelPosition"
    :label-width="labelWidth"
    :disabled="readonly"
    class="dynamic-form-renderer"
    @submit.prevent
  >
    <el-row :gutter="16">
      <template v-for="(f, idx) in fields" :key="f.id || idx">
        <el-col
          :span="f.fullWidth === false ? 12 : 24"
          class="form-col"
        >
          <!-- divider 不参与 form-item，仅占整行 -->
          <div v-if="f.type === 'DIVIDER'" class="divider-wrap">
            <component
              :is="resolveField(f.type)"
              :field="f"
              :readonly="readonly"
            />
          </div>
          <el-form-item
            v-else
            :prop="f.key"
            :required="!!f.required"
            :error="errorOf(f.key)"
          >
            <template #label>
              <span class="field-label">{{ f.label }}</span>
              <span v-if="f.required" class="required-mark">*</span>
              <el-tooltip v-if="f.help" :content="f.help" placement="top">
                <el-icon class="help-icon"><QuestionFilled /></el-icon>
              </el-tooltip>
            </template>
            <component
              :is="resolveField(f.type)"
              :field="f"
              :model-value="formData[f.key]"
              :readonly="readonly"
              @update:model-value="(v: any) => updateField(f.key, v)"
            />
          </el-form-item>
        </el-col>
      </template>
    </el-row>

    <div v-if="showSubmit" class="form-actions">
      <el-button @click="onCancel">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :icon="Check" @click="onSubmit">{{ t('common.submit') }}</el-button>
    </div>
  </el-form>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { QuestionFilled, Check } from '@element-plus/icons-vue';
import TextField from './fields/TextField.vue';
import TextareaField from './fields/TextareaField.vue';
import NumberField from './fields/NumberField.vue';
import SelectField from './fields/SelectField.vue';
import RadioField from './fields/RadioField.vue';
import CheckboxField from './fields/CheckboxField.vue';
import DateField from './fields/DateField.vue';
import DatetimeField from './fields/DatetimeField.vue';
import UserField from './fields/UserField.vue';
import DeptField from './fields/DeptField.vue';
import FileField from './fields/FileField.vue';
import RichTextField from './fields/RichTextField.vue';
import DividerField from './fields/DividerField.vue';
import type { FormFieldSchema, FieldType } from '@/api/formDesign';

const props = withDefaults(defineProps<{
  fields: FormFieldSchema[];
  modelValue?: Record<string, any>;
  readonly?: boolean;
  showSubmit?: boolean;
  labelPosition?: 'top' | 'left';
  labelWidth?: string;
}>(), {
  modelValue: () => ({}),
  readonly: false,
  showSubmit: false,
  labelPosition: 'top',
  labelWidth: '100px',
});

const emit = defineEmits<{
  'update:modelValue': [Record<string, any>];
  submit: [Record<string, any>];
  cancel: [];
}>();

const { t } = useI18n();

const formRef = ref();
const errorMap = reactive<Record<string, string>>({});

const formData = reactive<Record<string, any>>({ ...props.modelValue });

// 每次 props.modelValue 变化时，同步本地 formData（保留已有字段）
watch(() => props.modelValue, (v) => {
  for (const k of Object.keys(formData)) delete formData[k];
  Object.assign(formData, v);
}, { deep: true });

// 初始化：把每个字段的 defaultValue 填入
watch(() => props.fields, () => {
  for (const f of props.fields) {
    if (formData[f.key] == null && f.defaultValue != null && f.defaultValue !== '') {
      formData[f.key] = f.type === 'NUMBER' ? Number(f.defaultValue) : f.defaultValue;
    }
  }
}, { immediate: true });

const rules = computed(() => {
  const r: Record<string, any[]> = {};
  for (const f of props.fields) {
    if (f.type === 'DIVIDER') continue;
    const itemRules: any[] = [];
    if (f.required) itemRules.push({ required: true, message: t('formDesigner.required', { label: f.label }), trigger: 'blur' });
    if (f.validator?.pattern && (f.type === 'TEXT' || f.type === 'TEXTAREA')) {
      itemRules.push({ pattern: new RegExp(f.validator.pattern), message: f.validator.message || t('formDesigner.invalid'), trigger: 'blur' });
    }
    if (itemRules.length) r[f.key] = itemRules;
  }
  return r;
});

function resolveField(type: FieldType) {
  const map: Record<FieldType, any> = {
    TEXT: TextField,
    TEXTAREA: TextareaField,
    NUMBER: NumberField,
    SELECT: SelectField,
    RADIO: RadioField,
    CHECKBOX: CheckboxField,
    DATE: DateField,
    DATETIME: DatetimeField,
    USER: UserField,
    DEPT: DeptField,
    FILE: FileField,
    RICH_TEXT: RichTextField,
    DIVIDER: DividerField,
  };
  return map[type] || TextField;
}

function updateField(key: string, value: any) {
  formData[key] = value;
  emit('update:modelValue', { ...formData });
}

function errorOf(key: string) {
  return errorMap[key];
}

async function onSubmit() {
  if (!formRef.value) return;
  try {
    await formRef.value.validate();
    emit('submit', { ...formData });
  } catch (errors: any) {
    // 收集错误到 errorMap（el-form-item 同步显示）
    if (errors && typeof errors === 'object') {
      for (const k of Object.keys(errorMap)) delete errorMap[k];
      for (const [key, val] of Object.entries(errors)) {
        const msg = (val as any[])?.[0]?.message;
        if (msg) errorMap[key] = msg;
      }
    }
  }
}

function onCancel() {
  emit('cancel');
}

defineExpose({ validate: () => formRef.value?.validate(), formData });
</script>

<style scoped>
.dynamic-form-renderer { width: 100%; }
.form-col { margin-bottom: 16px; }
.field-label { color: var(--color-text); }
.required-mark { color: var(--color-danger); margin-left: 2px; }
.help-icon { margin-left: 4px; font-size: 12px; color: var(--color-text-muted); vertical-align: middle; }
.divider-wrap { width: 100%; }
.form-actions {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 16px;
  border-top: 1px solid var(--color-border-soft);
}
</style>