<template>
  <el-input-number
    v-model="model"
    :min="field.validator?.min"
    :max="field.validator?.max"
    :placeholder="field.placeholder || t('formDesigner.placeholder.number')"
    :disabled="readonly"
    controls-position="right"
    style="width: 100%;"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import type { FormFieldSchema } from '@/api/formDesign';

const props = defineProps<{ field: FormFieldSchema; modelValue?: number | null; readonly?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [number | null] }>();
const { t } = useI18n();

function toNumber(v: unknown): number | null {
  if (v == null || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

const model = computed<number | null>({
  get: () => props.modelValue ?? toNumber(props.field.defaultValue),
  set: v => emit('update:modelValue', v),
});
</script>