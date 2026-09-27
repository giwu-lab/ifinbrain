<template>
  <div class="portal-row" :style="{ gap: (props.gap ?? 16) + 'px' }">
    <slot />
  </div>
</template>

<script setup lang="ts">
const props = defineProps<{ gap?: number }>();
</script>

<style scoped>
.portal-row {
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  width: 100%;
}
.portal-row > :deep(*) { flex: 1 1 0; min-width: 0; }
</style>