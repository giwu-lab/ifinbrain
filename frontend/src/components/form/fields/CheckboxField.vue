<template>
  <el-checkbox-group v-model="model" :disabled="readonly">
    <span v-if="loading" class="options-loading">{{ t('formDesigner.loadingOptions') }}</span>
    <template v-else>
      <el-checkbox v-for="opt in options" :key="opt.value" :value="opt.value" :disabled="opt.disabled">
        {{ opt.label }}
      </el-checkbox>
    </template>
  </el-checkbox-group>
</template>

<script setup lang="ts">
import { computed, toRef } from 'vue';
import { useI18n } from 'vue-i18n';
import { useFieldOptions } from '@/composables/useFieldOptions';
import type { FormFieldSchema } from '@/api/formDesign';

const props = defineProps<{ field: FormFieldSchema; modelValue?: string[]; readonly?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [string[]] }>();
const { t } = useI18n();

const { options, loading } = useFieldOptions(toRef(props, 'field'));

function fromDefault(v?: string): string[] {
  return v ? v.split(',').filter(Boolean) : [];
}

const model = computed<string[]>({
  get: () => props.modelValue ?? fromDefault(props.field.defaultValue),
  set: v => emit('update:modelValue', v ?? []),
});
</script>

<style scoped>
.options-loading {
  font-size: 12px;
  color: var(--color-text-muted);
}
</style>