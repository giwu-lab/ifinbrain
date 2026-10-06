<script setup lang="ts">
/** 滚动列表：数据不够一屏时不滚；速度由 props.extra.speed 控制（px/s） */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import type { BiDataResp, BiWidget } from '@/api/bi';
import { colIndex } from '@/utils/bi/layout';

const props = defineProps<{
  widget: BiWidget;
  data: BiDataResp | null;
}>();

const box = ref<HTMLDivElement | null>(null);
const offset = ref(0);
let raf = 0;
let last = 0;

const maxRows = computed(() => Number(props.widget.extra?.maxRows ?? 8));

const rows = computed(() => {
  if (!props.data?.rows?.length) return [] as any[][];
  const bind = props.widget.data;
  const cols = props.data.columns;
  const keepIdx = [
    colIndex(cols, bind?.mapping.name),
    colIndex(cols, bind?.mapping.x),
    colIndex(cols, bind?.mapping.value),
  ].filter((i) => i >= 0);
  const idx = keepIdx.length ? keepIdx : cols.map((_, i) => i);
  return props.data.rows.slice(0, maxRows.value).map((r) => idx.map((i) => r[i]));
});

/** 高度不足以容纳两行时不滚动，避免抖动 */
const needScroll = computed(() => (box.value?.scrollHeight ?? 0) > (box.value?.clientHeight ?? 0) + 4);

function tick(ts: number) {
  if (!last) last = ts;
  const dt = (ts - last) / 1000;
  last = ts;
  const speed = Number(props.widget.extra?.speed ?? 24);
  if (needScroll.value) {
    offset.value = (offset.value + speed * dt) % Math.max(1, (box.value?.scrollHeight ?? 0) / 2);
  }
  raf = requestAnimationFrame(tick);
}

onMounted(() => { raf = requestAnimationFrame(tick); });
onBeforeUnmount(() => {
  cancelAnimationFrame(raf);
  raf = 0;
  last = 0;
});

function cellText(v: any) {
  return v === null || v === undefined || v === '' ? '-' : String(v);
}
</script>

<template>
  <div ref="box" class="w-marquee">
    <div v-if="!rows.length" class="empty">{{ $t('bi.view.noData') }}</div>
    <ul v-else class="list" :style="{ transform: `translateY(-${offset}px)` }">
      <li v-for="(r, i) in [...rows, ...rows]" :key="i" class="row">
        <span class="rank tabular">{{ i % rows.length + 1 }}</span>
        <span v-for="(c, j) in r" :key="j" class="cell" :class="{ num: j === r.length - 1 }">
          {{ cellText(c) }}
        </span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.w-marquee {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  min-height: 0;
}
.empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--bi-text-muted);
  font-size: 13px;
}
.list {
  list-style: none;
  margin: 0;
  padding: 0;
  will-change: transform;
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 4px;
  border-bottom: 1px solid var(--bi-border);
  font-size: 13px;
  color: var(--bi-text);
}
.rank {
  width: 20px;
  flex: 0 0 20px;
  color: var(--bi-text-muted);
}
.cell {
  flex: 1 1 0;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cell.num {
  flex: 0 0 auto;
  text-align: right;
  font-variant-numeric: tabular-nums;
  color: var(--bi-accent);
}
</style>