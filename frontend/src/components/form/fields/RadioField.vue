<template>
  <el-radio-group v-model="model" :disabled="readonly">
    <el-radio v-for="opt in (field.options || [])" :key="opt.value" :value="opt.value" :disabled="opt.disabled">
      {{ opt.label }}
    </el-radio>
  </el-radio-group>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { FormFieldSchema } from '@/api/formDesign';

const props = defineProps<{ field: FormFieldSchema; modelValue?: string; readonly?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [string] }>();

const model = computed<string>({
  get: () => props.modelValue ?? props.field.defaultValue ?? '',
  set: v => emit('update:modelValue', v ?? ''),
});
</script>