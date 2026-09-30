<!--
  FormFieldProps —— 字段属性面板（mfish 风格：分组 + 类型感知）
  分组：基础 / 选项 / 校验 / 高级
-->
<template>
  <aside class="form-field-props">
    <template v-if="!field">
      <div class="props-empty muted">
        <el-icon class="empty-icon"><Document /></el-icon>
        <p>{{ t('formDesigner.propsEmpty') }}</p>
      </div>
    </template>
    <template v-else>
      <div class="props-title">
        <span class="props-title-text">{{ t('formDesigner.propsTitle') }}</span>
        <span class="props-title-type muted">{{ typeLabel(field.type) }}</span>
      </div>

      <div class="props-scroll">
        <!-- ============ 基础属性 ============ -->
        <div class="props-section">
          <div class="props-section-label">{{ t('formDesigner.section.basic') }}</div>
          <el-form label-position="top" size="small" class="props-form">
            <el-form-item :label="t('formDesigner.field.label')" required>
              <el-input v-model="local.label" :placeholder="t('formDesigner.field.labelPh')" @input="emitPatch" />
            </el-form-item>
            <el-form-item :label="t('formDesigner.field.key')" required>
              <el-input v-model="local.key" :placeholder="t('formDesigner.field.keyPh')" @input="emitPatch" />
            </el-form-item>
            <el-form-item :label="t('formDesigner.field.type')">
              <el-select v-model="local.type" disabled style="width: 100%;">
                <el-option :value="local.type" :label="typeLabel(local.type)" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('formDesigner.field.placeholder')">
              <el-input v-model="local.placeholder" @input="emitPatch" />
            </el-form-item>
            <el-form-item :label="t('formDesigner.field.help')">
              <el-input v-model="local.help" type="textarea" :rows="2" @input="emitPatch" />
            </el-form-item>
            <el-form-item :label="t('formDesigner.field.defaultValue')">
              <el-input v-model="local.defaultValue" @input="emitPatch" />
            </el-form-item>
            <el-form-item v-if="field.type !== 'DIVIDER'">
              <el-checkbox v-model="local.required" @change="emitPatch">{{ t('formDesigner.field.required') }}</el-checkbox>
            </el-form-item>
          </el-form>
        </div>

        <!-- ============ 选项 ============ -->
        <div v-if="needsOptions" class="props-section">
          <div class="props-section-label">{{ t('formDesigner.field.options') }}</div>
          <div class="options-editor">
            <div v-for="(opt, idx) in (local.options || [])" :key="idx" class="option-row">
              <el-input v-model="opt.label" :placeholder="t('formDesigner.option.label')" size="small" style="flex: 1;" @input="emitPatch" />
              <el-input v-model="opt.value" :placeholder="t('formDesigner.option.value')" size="small" style="flex: 1;" @input="emitPatch" />
              <el-button link :icon="Delete" size="small" class="danger-icon" @click="removeOption(idx)" />
            </div>
            <el-button :icon="Plus" size="small" link @click="addOption">{{ t('formDesigner.option.add') }}</el-button>
          </div>
        </div>

        <!-- ============ 校验 ============ -->
        <div v-if="needsValidator" class="props-section">
          <div class="props-section-label">{{ t('formDesigner.field.validator') }}</div>
          <el-form label-position="top" size="small" class="props-form">
            <el-form-item :label="t('formDesigner.field.min')">
              <el-input-number v-model="local.validator!.min" :placeholder="t('formDesigner.field.minPh')" controls-position="right" style="width: 100%;" @change="emitPatch" />
            </el-form-item>
            <el-form-item :label="t('formDesigner.field.max')">
              <el-input-number v-model="local.validator!.max" :placeholder="t('formDesigner.field.maxPh')" controls-position="right" style="width: 100%;" @change="emitPatch" />
            </el-form-item>
            <el-form-item :label="t('formDesigner.field.pattern')">
              <el-input v-model="local.validator!.pattern" :placeholder="t('formDesigner.field.patternPh')" @input="emitPatch" />
            </el-form-item>
            <el-form-item :label="t('formDesigner.field.message')">
              <el-input v-model="local.validator!.message" @input="emitPatch" />
            </el-form-item>
          </el-form>
        </div>

        <!-- ============ 高级 ============ -->
        <div v-if="field.type !== 'DIVIDER'" class="props-section">
          <div class="props-section-label">{{ t('formDesigner.section.advanced') }}</div>
          <el-form label-position="top" size="small" class="props-form">
            <el-form-item>
              <el-checkbox v-model="local.fullWidth" @change="emitPatch">{{ t('formDesigner.field.fullWidth') }}</el-checkbox>
            </el-form-item>
          </el-form>
        </div>
      </div>
    </template>
  </aside>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { Document, Delete, Plus } from '@element-plus/icons-vue';
import type { FormFieldSchema, FieldType, FormFieldValidator, FormFieldOption } from '@/api/formDesign';

const props = defineProps<{ field: FormFieldSchema | null }>();
const emit = defineEmits<{ patch: [Partial<FormFieldSchema>] }>();
const { t } = useI18n();

const local = reactive<FormFieldSchema>({
  id: '', type: 'TEXT', key: '', label: '',
  required: false, defaultValue: '', placeholder: '', help: '',
  options: undefined, validator: undefined, fullWidth: true,
});

watch(() => props.field, (f) => {
  if (!f) return;
  Object.assign(local, JSON.parse(JSON.stringify(f)));
  if (!local.validator) local.validator = { min: undefined, max: undefined, pattern: '', message: '' } as FormFieldValidator;
  if (!local.options && needsOptions.value) local.options = [] as FormFieldOption[];
}, { immediate: true });

const needsOptions = computed(() =>
  local.type === 'SELECT' || local.type === 'RADIO' || local.type === 'CHECKBOX'
);
const needsValidator = computed(() =>
  local.type === 'TEXT' || local.type === 'TEXTAREA' || local.type === 'NUMBER'
);

function typeLabel(type: FieldType): string {
  return t(`formDesigner.type.${type}`);
}

function addOption() {
  if (!local.options) local.options = [];
  local.options.push({ label: '', value: '' });
  emitPatch();
}

function removeOption(idx: number) {
  if (!local.options) return;
  local.options.splice(idx, 1);
  emitPatch();
}

function emitPatch() {
  emit('patch', { ...local });
}
</script>

<style scoped>
.form-field-props {
  width: 100%;
  background: var(--color-bg-elev);
  border-left: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.props-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  text-align: center;
  padding: 60px 16px;
  font-size: 13px;
}
.empty-icon { font-size: 36px; color: var(--color-text-muted); margin-bottom: 12px; }

.props-title {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  padding: 12px 12px 10px;
  border-bottom: 1px solid var(--color-border-soft);
}
.props-title-text {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.props-title-text::before {
  content: '';
  display: inline-block;
  width: 4px;
  height: 14px;
  background: var(--color-primary);
  border-radius: 2px;
}
.props-title-type {
  font-size: 11px;
  padding: 1px 6px;
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  border-radius: 2px;
}

.props-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 4px 0 16px;
}
.props-section { padding: 8px 12px 4px; }
.props-section-label {
  font-size: 10px;
  font-weight: 600;
  color: var(--color-text-muted);
  margin: 4px 0 8px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.props-form :deep(.el-form-item) { margin-bottom: 14px; }

.options-editor { padding: 0; }
.option-row {
  display: flex;
  gap: 4px;
  margin-bottom: 6px;
  align-items: center;
}
.danger-icon :deep(.el-icon) { color: var(--color-danger); }
</style>