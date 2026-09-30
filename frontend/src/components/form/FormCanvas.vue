<!--
  FormCanvas —— 三模式画布（mfish 风格）：
    build   — 卡片列表，支持选中/上下移/复制/删除
    preview — 动态表单渲染（按当前 schema 渲染 readonly 表单）
    json    — 字段 JSON 展示 + 复制按钮
  deviceMode 决定 canvasWidth（desktop/tablet/mobile），zoom 缩放。
-->
<template>
  <div class="form-canvas" :class="{ 'is-empty': !fields.length }">
    <template v-if="!fields.length">
      <div class="canvas-empty muted">{{ t('formDesigner.canvasEmpty') }}</div>
    </template>

    <!-- =================== BUILD =================== -->
    <div
      v-else-if="mode === 'build'"
      class="canvas-build"
      :style="{ width: canvasWidth + 'px', transform: `scale(${zoom})`, transformOrigin: 'top left' }"
    >
      <div class="canvas-build-inner">
        <div
          v-for="(f, idx) in fields"
          :key="f.id"
          class="canvas-row"
          :class="{ 'is-selected': selectedId === f.id }"
          @click.stop="select(f.id)"
        >
          <div class="row-index muted">{{ idx + 1 }}</div>
          <div class="row-content">
            <div class="row-header">
              <span class="row-label">
                {{ f.label || t('formDesigner.unnamed') }}
                <span v-if="f.required" class="required-mark">*</span>
              </span>
              <span class="row-type muted">{{ typeLabel(f.type) }}</span>
              <span v-if="f.key" class="row-key muted">{{ f.key }}</span>
            </div>
            <div class="row-body">
              <component :is="resolveField(f.type)" :field="f" :readonly="true" />
            </div>
          </div>
          <div class="row-actions">
            <el-tooltip :content="t('common.up')" placement="top">
              <el-button link :icon="ArrowUp" size="small" :disabled="idx === 0" @click.stop="moveField(f.id, 'up')" />
            </el-tooltip>
            <el-tooltip :content="t('common.down')" placement="top">
              <el-button link :icon="ArrowDown" size="small" :disabled="idx === fields.length - 1" @click.stop="moveField(f.id, 'down')" />
            </el-tooltip>
            <el-tooltip :content="t('common.duplicate')" placement="top">
              <el-button link :icon="DocumentCopy" size="small" @click.stop="duplicate(f.id)" />
            </el-tooltip>
            <el-tooltip :content="t('common.delete')" placement="top">
              <el-button link :icon="Delete" size="small" class="danger-icon" @click.stop="remove(f.id)" />
            </el-tooltip>
          </div>
        </div>
      </div>
    </div>

    <!-- =================== PREVIEW =================== -->
    <div
      v-else-if="mode === 'preview'"
      class="canvas-preview"
      :style="{ width: canvasWidth + 'px', transform: `scale(${zoom})`, transformOrigin: 'top center' }"
    >
      <DynamicFormRenderer
        :fields="fields"
        :model-value="previewData"
        :readonly="false"
        @update:model-value="(v) => previewData = v"
      />
    </div>

    <!-- =================== JSON =================== -->
    <div v-else class="canvas-json">
      <div class="json-card">
        <div class="json-header">
          <span class="json-title">
            <el-icon><Document /></el-icon>
            <span>{{ t('formDesigner.jsonTitle') }}</span>
          </span>
          <el-button :icon="CopyDocument" size="small" @click="copyJson">{{ t('formDesigner.jsonCopy') }}</el-button>
        </div>
        <pre class="json-content">{{ jsonText }}</pre>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { ArrowUp, ArrowDown, DocumentCopy, Delete, Document, CopyDocument } from '@element-plus/icons-vue';
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
import DynamicFormRenderer from './FormRenderer.vue';
import type { FormFieldSchema, FieldType } from '@/api/formDesign';
import type { DesignerMode } from '@/stores/formDesign';

const props = defineProps<{
  fields: FormFieldSchema[];
  selectedId: string | null;
  mode: DesignerMode;
  canvasWidth: number;
  zoom: number;
}>();
const emit = defineEmits<{
  select: [id: string];
  remove: [id: string];
  move: [id: string, direction: 'up' | 'down'];
  duplicate: [id: string];
}>();
const { t } = useI18n();

const previewData = ref<Record<string, any>>({});

const jsonText = computed(() =>
  JSON.stringify({ version: 1, fields: props.fields }, null, 2)
);

function select(id: string) { emit('select', id); }
function remove(id: string) { emit('remove', id); }
function moveField(id: string, direction: 'up' | 'down') { emit('move', id, direction); }
function duplicate(id: string) { emit('duplicate', id); }

function typeLabel(type: FieldType): string {
  return t(`formDesigner.type.${type}`);
}

function resolveField(type: FieldType) {
  const map: Record<FieldType, any> = {
    TEXT: TextField, TEXTAREA: TextareaField, NUMBER: NumberField,
    SELECT: SelectField, RADIO: RadioField, CHECKBOX: CheckboxField,
    DATE: DateField, DATETIME: DatetimeField,
    USER: UserField, DEPT: DeptField,
    FILE: FileField, RICH_TEXT: RichTextField, DIVIDER: DividerField,
  };
  return map[type] || TextField;
}

async function copyJson() {
  try {
    await navigator.clipboard.writeText(jsonText.value);
    ElMessage.success(t('design.copied'));
  } catch {
    ElMessage.warning('Clipboard not available');
  }
}
</script>

<style scoped>
.form-canvas {
  width: 100%;
  height: 100%;
  overflow: auto;
  background: var(--color-border-soft);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 24px;
}
.form-canvas.is-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  font-size: 13px;
  padding: 80px 24px;
}

.canvas-build,
.canvas-preview {
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 24px;
  margin: 0 auto;
}
.canvas-build-inner { min-height: 200px; }

.canvas-row {
  display: flex;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xs);
  background: var(--color-bg);
  margin-bottom: 10px;
  cursor: pointer;
  transition: border-color 0.1s, background 0.1s;
}
.canvas-row:hover { border-color: var(--color-primary); }
.canvas-row.is-selected {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
}
.row-index {
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  min-width: 24px;
  display: flex;
  align-items: flex-start;
  padding-top: 4px;
}
.row-content { flex: 1; min-width: 0; }
.row-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 13px;
}
.row-label { font-weight: 500; color: var(--color-text); }
.required-mark { color: var(--color-danger); margin-left: 2px; }
.row-type {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 2px;
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  color: var(--color-text-secondary);
}
.row-key {
  font-family: 'SF Mono', 'JetBrains Mono', Menlo, monospace;
  font-size: 11px;
  color: var(--color-text-muted);
}
.row-body {
  background: var(--color-bg);
  border: 1px dashed var(--color-border-soft);
  border-radius: var(--radius-xs);
  padding: 8px;
  pointer-events: none;
}
.row-actions { display: flex; flex-direction: column; gap: 2px; align-items: center; }
.danger-icon :deep(.el-icon) { color: var(--color-danger); }

/* JSON mode */
.canvas-json { width: 100%; max-width: 1200px; }
.json-card {
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.json-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--color-border-soft);
  background: var(--color-bg-elev);
}
.json-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  font-size: 13px;
}
.json-content {
  margin: 0;
  padding: 16px;
  font-family: 'SF Mono', 'JetBrains Mono', Menlo, monospace;
  font-size: 12px;
  line-height: 1.6;
  color: var(--color-text);
  background: var(--color-bg);
  max-height: 600px;
  overflow: auto;
  white-space: pre;
}
</style>