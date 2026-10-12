<template>
  <el-select
    v-model="model"
    :placeholder="field.placeholder || t('formDesigner.placeholder.select')"
    :disabled="readonly"
    :loading="loading"
    clearable
    style="width: 100%;"
  >
    <el-option
      v-for="opt in options"
      :key="opt.value"
      :label="opt.label"
      :value="opt.value"
      :disabled="opt.disabled"
    />
  </el-select>
</template>

<script setup lang="ts">
import { computed, toRef } from 'vue';
import { useI18n } from 'vue-i18n';
import { useFieldOptions } from '@/composables/useFieldOptions';
import type { FormFieldSchema } from '@/api/formDesign';

const props = defineProps<{ field: FormFieldSchema; modelValue?: string; readonly?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [string] }>();
const { t } = useI18n();

// 配了 optionsSource 就取动态选项，没配直接用 schema 里的静态 options（零请求）
const { options, loading } = useFieldOptions(toRef(props, 'field'));

const model = computed<string>({
  get: () => props.modelValue ?? props.field.defaultValue ?? '',
  set: v => emit('update:modelValue', v ?? ''),
});
</script>