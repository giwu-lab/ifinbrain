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
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { useUserOptions } from '@/composables/useOptionsCache';
import type { FormFieldSchema } from '@/api/formDesign';

const props = defineProps<{ field: FormFieldSchema; modelValue?: string; readonly?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [string] }>();
const { t } = useI18n();

const userOptions = useUserOptions();

const model = computed<string>({
  get: () => props.modelValue ?? props.field.defaultValue ?? '',
  set: v => emit('update:modelValue', v ?? ''),
});
</script>