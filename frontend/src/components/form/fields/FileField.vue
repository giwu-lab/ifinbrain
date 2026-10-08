<template>
  <el-upload
    :file-list="fileList"
    :disabled="readonly"
    :on-change="onChange"
    :on-remove="onRemove"
    :before-upload="beforeUpload"
    :drag="!compact"
    :show-file-list="!compact"
    class="file-field"
    :class="{ 'is-compact': compact }"
  >
    <!-- compact：表格单元格内用，避免 drag 大块撑爆行高 -->
    <el-button v-if="compact" size="small" :icon="Paperclip" link type="primary">
      {{ fileList.length ? t('formDesigner.upload.replace') : t('formDesigner.upload.choose') }}
    </el-button>
    <template v-else>
      <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
      <div class="el-upload__text">{{ t('formDesigner.upload.dropOrClick') }}</div>
    </template>
  </el-upload>
  <div v-if="compact && fileList.length" class="compact-files">
    <span v-for="(f, i) in fileList" :key="i" class="compact-file" :title="f.name">
      <el-icon><Document /></el-icon>{{ f.name }}
    </span>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { Paperclip, Document } from '@element-plus/icons-vue';
import type { FormFieldSchema } from '@/api/formDesign';

const props = defineProps<{
  field: FormFieldSchema;
  modelValue?: string;
  readonly?: boolean;
  /** 表格单元格内渲染：单行按钮 + 文件名列表，而不是整块拖拽区 */
  compact?: boolean;
}>();
const emit = defineEmits<{ 'update:modelValue': [string] }>();
const { t } = useI18n();

const fileList = ref<Array<{ name: string; url: string }>>([]);

watch(() => props.modelValue, (v) => {
  if (!v) { fileList.value = []; return; }
  try {
    const arr = JSON.parse(v);
    if (Array.isArray(arr)) fileList.value = arr;
  } catch { /* ignore */ }
}, { immediate: true });

function beforeUpload(file: File) {
  // 简化：本期存 filename JSON，不实际上传；后续接入 storage 子系统
  fileList.value = [...fileList.value, { name: file.name, url: '' }];
  emitValue();
  return false;
}

function onChange(_file: any, list: any[]) {
  fileList.value = list;
  emitValue();
}

function onRemove(_file: any, list: any[]) {
  fileList.value = list;
  emitValue();
}

function emitValue() {
  emit('update:modelValue', JSON.stringify(fileList.value));
}
</script>

<style scoped>
/* compact 模式：整块不换行，文件名单行省略，避免多行时把表格行撑高 */
.is-compact :deep(.el-upload),
.is-compact :deep(.el-upload-dragger) { display: inline-flex; }
.compact-files {
  margin-top: 2px;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.compact-file {
  display: flex;
  align-items: center;
  gap: 3px;
  font-size: 11px;
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>