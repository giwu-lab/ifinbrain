<template>
  <el-popover
    placement="top-start"
    :width="380"
    trigger="click"
    :show-arrow="false"
    popper-class="cell-popover"
    :disabled="!overflowing"
  >
    <template #reference>
      <span ref="el" class="truncate" :class="{ clickable: overflowing }">{{ text }}</span>
    </template>
    <div class="popover-body">{{ text }}</div>
  </el-popover>
</template>

<script setup lang="ts">
import { computed, onMounted, onUpdated, ref } from 'vue';

const props = defineProps<{ text?: string | null }>();
const text = computed(() => props.text ?? '');

const el = ref<HTMLElement | null>(null);
const overflowing = ref(false);

function check() {
  const n = el.value;
  if (!n) return;
  overflowing.value = n.scrollWidth > n.clientWidth + 1;
}
onMounted(check);
onUpdated(check);
</script>

<style scoped>
.truncate {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}
.clickable { cursor: pointer; }
</style>

<style>
.cell-popover { padding: 8px 12px !important; }
.popover-body {
  font-size: 13px;
  line-height: 1.5;
  color: var(--color-text);
  word-break: break-all;
  white-space: pre-wrap;
  max-height: 320px;
  overflow: auto;
}
</style>