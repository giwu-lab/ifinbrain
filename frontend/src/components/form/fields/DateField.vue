<template>
  <el-date-picker
    v-model="model"
    type="date"
    :placeholder="field.placeholder || t('formDesigner.placeholder.date')"
    :disabled="readonly"
    value-format="YYYY-MM-DD"
    style="width: 100%;"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import type { FormFieldSchema } from '@/api/formDesign';

const props = defineProps<{ field: FormFieldSchema; modelValue?: string; readonly?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [string] }>();
const { t } = useI18n();

const model = computed<string>({
  get: () => props.modelValue ?? props.field.defaultValue ?? '',
  set: v => emit('update:modelValue', v ?? ''),
});
</script>