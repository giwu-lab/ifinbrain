<template>
  <el-tree-select
    v-model="model"
    :data="deptOptions"
    :placeholder="field.placeholder || t('formDesigner.placeholder.dept')"
    :disabled="readonly"
    check-strictly
    clearable
    style="width: 100%;"
  />
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import type { FormFieldSchema } from '@/api/formDesign';

const props = defineProps<{ field: FormFieldSchema; modelValue?: string; readonly?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [string] }>();
const { t } = useI18n();

const deptOptions = ref<any[]>([]);

onMounted(async () => {
  try {
    const { http } = await import('@/api/http');
    const data = await http.get<any, any>('/system/depts/tree');
    deptOptions.value = Array.isArray(data) ? data : [];
  } catch {
    ElMessage.warning(t('formDesigner.warning.deptLoadFailed'));
  }
});

const model = computed<string>({
  get: () => props.modelValue ?? props.field.defaultValue ?? '',
  set: v => emit('update:modelValue', v ?? ''),
});
</script>