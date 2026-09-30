<template>
  <div class="rich-text-field">
    <textarea
      v-model="model"
      :placeholder="field.placeholder || t('formDesigner.placeholder.richText')"
      :disabled="readonly"
      rows="6"
    />
    <div class="rich-text-hint muted small">{{ t('formDesigner.hint.richText') }}</div>
  </div>
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

<style scoped>
.rich-text-field textarea {
  width: 100%;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xs);
  padding: 8px 10px;
  font-family: inherit;
  resize: vertical;
}
.rich-text-hint {
  margin-top: 4px;
  font-size: 11px;
  color: var(--color-text-muted);
}
</style>