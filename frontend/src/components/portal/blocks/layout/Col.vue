<template>
  <div class="portal-col">
    <slot />
  </div>
</template>

<script setup lang="ts">
const props = defineProps<{ span?: number }>();
</script>

<style scoped>
.portal-col {
  /* span is conceptual; in flat mode children flow normally.
     Container width is decided by parent row's flex layout. */
  display: block;
  min-width: 0;
}
</style>