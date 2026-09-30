<template>
  <el-select
    v-model="model"
    :placeholder="field.placeholder || t('formDesigner.placeholder.user')"
    :disabled="readonly"
    filterable
    clearable
    style="width: 100%;"
  >
    <el-option
      v-for="u in userOptions"
      :key="u.id"
      :label="u.label"
      :value="String(u.id)"
    />
  </el-select>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import type { FormFieldSchema } from '@/api/formDesign';

const props = defineProps<{ field: FormFieldSchema; modelValue?: string; readonly?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [string] }>();
const { t } = useI18n();

const userOptions = ref<Array<{ id: number | string; label: string }>>([]);

onMounted(async () => {
  try {
    // 复用系统用户 API
    const { http } = await import('@/api/http');
    const data = await http.get<any, any>('/system/users', { params: { page: 1, size: 200 } });
    const list: any[] = Array.isArray(data) ? data : ((data as any)?.records ?? (data as any)?.items ?? []);
    userOptions.value = list.map((u: any) => ({ id: u.id, label: u.username + (u.nickname ? ` (${u.nickname})` : '') }));
  } catch {
    ElMessage.warning(t('formDesigner.warning.userLoadFailed'));
  }
});

const model = computed<string>({
  get: () => props.modelValue ?? props.field.defaultValue ?? '',
  set: v => emit('update:modelValue', v ?? ''),
});
</script>