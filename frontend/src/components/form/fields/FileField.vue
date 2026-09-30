<template>
  <el-upload
    :file-list="fileList"
    :disabled="readonly"
    :on-change="onChange"
    :on-remove="onRemove"
    :before-upload="beforeUpload"
    drag
  >
    <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
    <div class="el-upload__text">{{ t('formDesigner.upload.dropOrClick') }}</div>
  </el-upload>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { FormFieldSchema } from '@/api/formDesign';

const props = defineProps<{ field: FormFieldSchema; modelValue?: string; readonly?: boolean }>();
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