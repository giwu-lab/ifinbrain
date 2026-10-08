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
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { useDeptOptions } from '@/composables/useOptionsCache';
import type { FormFieldSchema } from '@/api/formDesign';

const props = defineProps<{ field: FormFieldSchema; modelValue?: string; readonly?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [string] }>();
const { t } = useI18n();

const deptOptions = useDeptOptions();

const model = computed<string>({
  get: () => props.modelValue ?? props.field.defaultValue ?? '',
  set: v => emit('update:modelValue', v ?? ''),
});
</script>