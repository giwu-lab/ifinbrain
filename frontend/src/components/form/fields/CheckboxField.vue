<template>
  <el-checkbox-group v-model="model" :disabled="readonly">
    <el-checkbox v-for="opt in (field.options || [])" :key="opt.value" :value="opt.value" :disabled="opt.disabled">
      {{ opt.label }}
    </el-checkbox>
  </el-checkbox-group>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { FormFieldSchema } from '@/api/formDesign';

const props = defineProps<{ field: FormFieldSchema; modelValue?: string[]; readonly?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [string[]] }>();

function fromDefault(v?: string): string[] {
  return v ? v.split(',').filter(Boolean) : [];
}

const model = computed<string[]>({
  get: () => props.modelValue ?? fromDefault(props.field.defaultValue),
  set: v => emit('update:modelValue', v ?? []),
});
</script>