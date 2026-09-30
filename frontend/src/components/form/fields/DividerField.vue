<template>
  <el-divider v-if="field.label" content-position="left">
    <span class="divider-label">{{ field.label }}</span>
  </el-divider>
  <el-divider v-else />
</template>

<script setup lang="ts">
import type { FormFieldSchema } from '@/api/formDesign';

defineProps<{ field: FormFieldSchema; modelValue?: any; readonly?: boolean }>();
</script>

<style scoped>
.divider-label {
  font-weight: 600;
  color: var(--color-text-secondary);
  font-size: 13px;
}
</style>