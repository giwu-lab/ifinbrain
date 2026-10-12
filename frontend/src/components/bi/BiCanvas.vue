<script setup lang="ts">
/**
 * 栅格画布：拖拽移动 + 8 向缩放 + 吸附对齐。
 *
 * 撤销粒度约定：pointermove 只改内存（updateWidget(..., false)），
 * pointerup 时统一 commitGeometry() 压一帧栈 —— 否则鼠标每动一像素压一帧。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import type { BiWidget, DashboardCanvas, DashboardTheme } from '@/api/bi';
import { cellSize, gridRectPx, presetVars } from '@/utils/bi/layout';
import type { DataSlot } from '@/composables/useBiData';
import BiWidgetFrame from './BiWidgetFrame.vue';

const props = withDefaults(defineProps<{
  canvas: DashboardCanvas;
  widgets: BiWidget[];
  theme: DashboardTheme;
  /** 主选中项（右侧属性面板看它） */
  selectedId?: string | null;
  /** 多选集合；不传时退化成只有 selectedId，单选路径行为不变 */
  selectedIds?: string[];
  editable?: boolean;
  zoom?: number;
  showGrid?: boolean;
  dataFor: (w: BiWidget) => DataSlot | undefined;
  onSelect: (id: string | null) => void;
  onToggleSelect?: (id: string) => void;
  onSetSelection?: (ids: string[]) => void;
  onMove: (id: string, patch: Partial<BiWidget>) => void;
  onCommit: (id: string) => void;
}>(), {
  selectedId: null,
  selectedIds: undefined,
  editable: false,
  zoom: 1,
  showGrid: false,
});

const selectedSet = computed(() => {
  if (props.selectedIds?.length) return new Set(props.selectedIds);
  return props.selectedId ? new Set([props.selectedId]) : new Set<string>();
});

/**
 * 把「本容器实际能给画布用的净尺寸」报上去，设计器据此算 fit。
 *
 * 之前是设计器去量 `.pane-center`，画布却在 `.canvas-viewport` 里排版 —— 两者之间
 * 隔着 flex 布局与 keep-alive，量到的值偶尔比真实可用区大，于是 fit 给出一个
 * 放不下的比例，画布底部/右侧冒出横向滚动条。让「知道盒子的人」自己报，
 * 量的人和用的人永远是同一个元素，不存在对不上的中间层。
 * 报的是扣掉 padding 之后的**内容盒**，调用方直接除画布宽高即可。
 */
const emit = defineEmits<{ (e: 'available', box: { w: number; h: number }): void }>();

const root = ref<HTMLDivElement | null>(null);

function reportAvailable() {
  const el = root.value;
  if (!el) return;
  const cs = getComputedStyle(el);
  const px = (v: string) => parseFloat(v) || 0;
  emit('available', {
    w: Math.max(0, el.clientWidth - px(cs.paddingLeft) - px(cs.paddingRight)),
    h: Math.max(0, el.clientHeight - px(cs.paddingTop) - px(cs.paddingBottom)),
  });
}

const cellPx = computed(() => cellSize(props.canvas));
const cw = computed(() => cellPx.value.cw);
const ch = computed(() => cellPx.value.ch);
const stepX = computed(() => cw.value + props.canvas.gap);
const stepY = computed(() => ch.value + props.canvas.gap);

const ordered = computed(() => [...props.widgets].sort((a, b) => a.z - b.z));

/**
 * 主题 CSS 变量必须落在画布根元素上，否则组件样式里的 var(--bi-panel) /
 * var(--bi-border) 解析不到 —— 表现为「选了深蓝主题但面板还是白的」。
 * 播放页 View.vue 把变量挂在 .bi-view 根上，设计器这边由这里补上，两条路径都要有。
 */
const themeVarsStyle = computed(() => presetVars(props.theme.preset, props.theme.accent));

const stageBoxStyle = computed(() => ({
  width: `${props.canvas.width}px`,
  height: `${props.canvas.height}px`,
  transform: `scale(${props.zoom || 1})`,
}));

/**
 * 缩放后的占位尺寸。用它做 flex 居中，transform 只负责缩放内容。
 * 直接 flex 居中一个 1920px 宽的元素会算错 —— transform 不改变布局尺寸，
 * 元素仍按 1920px 参与布局，偏移量算大/算小都会导致画布被裁掉一侧。
 */
const sizerStyle = computed(() => {
  const s = props.zoom || 1;
  return {
    width: `${props.canvas.width * s}px`,
    height: `${props.canvas.height * s}px`,
  };
});

function rectOf(w: BiWidget) {
  return gridRectPx(props.canvas, { x: w.x, y: w.y, w: w.w, h: w.h });
}

/**
 * 只用来盯容器尺寸变化并上报可用区。
 * 注意**不要**在这里去 resize 图表：缩放走的是 transform，组件的布局尺寸没变，
 * 各图表自己的 ResizeObserver 不会误触发，ECharts 也不需要重排。
 */
let ro: ResizeObserver | null = null;

/** 栅格底纹：两条 repeating-linear-gradient，比逐格画 DOM 便宜得多 */
const gridStyle = computed(() => {
  if (!props.showGrid) return {};
  return {
    backgroundImage: [
      `linear-gradient(to right, var(--bi-border) 1px, transparent 1px)`,
      `linear-gradient(to bottom, var(--bi-border) 1px, transparent 1px)`,
    ].join(','),
    backgroundSize: `${stepX.value}px ${stepY.value}px`,
  };
});

// ===================== 手势状态 =====================

type Gesture =
  | { kind: 'move'; id: string; startX: number; startY: number; /** 每个参与移动的组件的起始格坐标 */ origins: Map<string, { x: number; y: number }> }
  | { kind: 'resize'; id: string; dir: string; startX: number; startY: number; o: { x: number; y: number; w: number; h: number } };

let gesture: Gesture | null = null;

function onPointerDownBody(ev: PointerEvent, id: string) {
  if (!props.editable) return;
  const w = props.widgets.find((x) => x.id === id);
  if (!w || w.locked) return;
  ev.preventDefault();
  // 多选状态下拖任意一个 = 拖整组。逐个记起始坐标，这样整组保持相对位置
  const inSel = selectedSet.value.has(id) && selectedSet.value.size > 1;
  const ids = inSel ? Array.from(selectedSet.value) : [id];
  const origins = new Map<string, { x: number; y: number }>();
  for (const sid of ids) {
    const t = props.widgets.find((x) => x.id === sid);
    if (t && !t.locked) origins.set(sid, { x: t.x, y: t.y });
  }
  gesture = { kind: 'move', id, startX: ev.clientX, startY: ev.clientY, origins };
  window.addEventListener('pointermove', onMove);
  window.addEventListener('pointerup', onUp, { once: true });
}

function onPointerDownHandle(ev: PointerEvent, id: string, dir: string) {
  if (!props.editable) return;
  const w = props.widgets.find((x) => x.id === id);
  if (!w || w.locked) return;
  ev.preventDefault();
  gesture = {
    kind: 'resize', id, dir,
    startX: ev.clientX, startY: ev.clientY,
    o: { x: w.x, y: w.y, w: w.w, h: w.h },
  };
  window.addEventListener('pointermove', onMove);
  window.addEventListener('pointerup', onUp, { once: true });
}

function onMove(ev: PointerEvent) {
  if (!gesture) return;
  // 屏幕 px → 画布 px（画布被 zoom 缩放过）
  const dx = (ev.clientX - gesture.startX) / (props.zoom || 1);
  const dy = (ev.clientY - gesture.startY) / (props.zoom || 1);
  const gx = Math.round(dx / stepX.value);
  const gy = Math.round(dy / stepY.value);

  if (gesture.kind === 'move') {
    const { id, origins } = gesture;
    // 整组一起平移：每个组件用**自己的**起始坐标 + 同一个位移，
    // 不能拿主选中项的起点去算，否则组内其它组件会跳到同一个位置
    for (const [sid, o] of origins) {
      const t = props.widgets.find((x) => x.id === sid);
      if (!t) continue;
      props.onMove(sid, {
        x: Math.max(0, Math.min(o.x + gx, props.canvas.gridCols - t.w)),
        y: Math.max(0, Math.min(o.y + gy, props.canvas.gridRows - t.h)),
      });
    }
    void id;
    return;
  }

  // resize：nw / sw 动 x+w，ne / se 只动 w，以此类推
  const { id, dir, o } = gesture;
  let { x, y, w: nw, h: nh } = o;
  if (dir.includes('e')) nw = o.w + gx;
  if (dir.includes('s')) nh = o.h + gy;
  if (dir.includes('w')) { nw = o.w - gx; x = o.x + gx; }
  if (dir.includes('n')) { nh = o.h - gy; y = o.y + gy; }
  props.onMove(id, {
    x, y,
    w: Math.max(1, nw),
    h: Math.max(1, nh),
  });
}

function onUp() {
  window.removeEventListener('pointermove', onMove);
  const g = gesture;
  gesture = null;
  if (g) props.onCommit(g.id);
}

/** 点击组件：Ctrl / Shift / Meta 为增删选，否则单选 */
function onFrameSelect(id: string) {
  if (props.onToggleSelect && selectedSet.value.size > 1) {
    props.onToggleSelect(id);
    return;
  }
  props.onSelect(id);
}

/** 点击空白处取消选中（框选逻辑在 onBandDown 里统一处理） */
function onCanvasPointerDown(ev: PointerEvent) {
  if (ev.target === root.value) props.onSelect(null);
}

/** 双击组件置顶 */
function onDblClick(ev: MouseEvent, id: string) {
  if (!props.editable) return;
  const w = props.widgets.find((x) => x.id === id);
  if (!w) return;
  const top = Math.max(...props.widgets.map((v) => v.z));
  if (w.z !== top) props.onMove(id, { z: top + 1 });
}

// ===================== 框选 =====================

/**
 * 框选（rubber band）。只在空白处按下才起框；组件上的按下仍然是拖动，
 * 否则按住组件往外拖就变成「拖组件 + 框选」打架。
 *
 * 命中判定用**栅格**而不是像素相交：widget 坐标本来就是格，
 * 两个矩形相交判定转成「格区间相交」更短，也不会出现差一像素没选中的情况。
 */
type Box = { x: number; y: number; w: number; h: number };
let band: { startX: number; startY: number; curX: number; curY: number } | null = null;
const bandStyle = computed<Box | null>(() => {
  if (!band) return null;
  const x = Math.min(band.startX, band.curX);
  const y = Math.min(band.startY, band.curY);
  return { x, y, w: Math.abs(band.curX - band.startX), h: Math.abs(band.curY - band.startY) };
});

function onBandDown(ev: PointerEvent) {
  if (!props.editable || ev.button !== 0) return;
  const rect = root.value?.getBoundingClientRect();
  if (!rect) return;
  band = {
    startX: ev.clientX - rect.left,
    startY: ev.clientY - rect.top,
    curX: ev.clientX - rect.left,
    curY: ev.clientY - rect.top,
  };
  window.addEventListener('pointermove', onBandMove);
  window.addEventListener('pointerup', onBandUp, { once: true });
}

function onBandMove(ev: PointerEvent) {
  const rect = root.value?.getBoundingClientRect();
  if (!band || !rect) return;
  band.curX = ev.clientX - rect.left;
  band.curY = ev.clientY - rect.top;
}

function onBandUp(ev: PointerEvent) {
  window.removeEventListener('pointermove', onBandMove);
  const b = band;
  band = null;
  if (!b) return;
  const s = props.zoom || 1;
  // 拖动距离很小（< 4 屏幕像素）视为「点空白」→ 清空选择，而不是框选到一个空集
  if (Math.abs(b.curX - b.startX) < 4 && Math.abs(b.curY - b.startY) < 4) {
    props.onSelect(null);
    return;
  }
  const box: Box = {
    x: Math.min(b.startX, b.curX) / s,
    y: Math.min(b.startY, b.curY) / s,
    w: Math.abs(b.curX - b.startX) / s,
    h: Math.abs(b.curY - b.startY) / s,
  };
  const hits = props.widgets
    .filter((w) => {
      if (w.hidden) return false;
      const r = rectOf(w);
      return r.left < box.x + box.w && r.left + r.width > box.x
          && r.top < box.y + box.h && r.top + r.height > box.y;
    })
    .map((w) => w.id);
  // 加选：按住 Ctrl/Shift 时与现有选择求并集
  if ((ev.ctrlKey || ev.metaKey || ev.shiftKey) && props.selectedIds?.length) {
    props.onSetSelection?.(Array.from(new Set([...props.selectedIds, ...hits])));
  } else {
    props.onSetSelection?.(hits);
  }
}

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onBandMove);
  window.removeEventListener('pointerup', onBandUp);
});

onMounted(() => {
  reportAvailable();
  if (root.value && typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver(() => reportAvailable());
    ro.observe(root.value);
  }
});
onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onMove);
  window.removeEventListener('pointerup', onUp);
  ro?.disconnect();
  ro = null;
  gesture = null;
});
</script>

<template>
  <div ref="root" class="canvas-viewport">
    <div class="canvas-sizer" :style="sizerStyle">
      <div
        class="canvas-stage"
        :style="[stageBoxStyle, themeVarsStyle]"
      >
      <!-- 栅格底纹 -->
      <div class="canvas-bg" :style="gridStyle" @pointerdown="onBandDown"></div>

      <!-- 框选矩形（画布内容坐标，已扣掉 zoom） -->
      <div v-if="bandStyle" class="select-band" :style="bandStyle"></div>

      <BiWidgetFrame
        v-for="w in ordered"
        :key="w.id"
        :widget="w"
        :theme="theme"
        :selected="selectedSet.has(w.id)"
        :editable="editable"
        :loading="dataFor(w)?.loading ?? false"
        :error="dataFor(w)?.error ?? ''"
        :last-ok-at="dataFor(w)?.lastOkAt ?? 0"
        :data="dataFor(w)?.data ?? null"
        :left="rectOf(w).left"
        :top="rectOf(w).top"
        :width="rectOf(w).width"
        :height="rectOf(w).height"
        :z-index="w.z + 1"
        @select="onFrameSelect"
        @pointerdown-body="onPointerDownBody"
        @pointerdown-handle="onPointerDownHandle"
        @dblclick="onDblClick($event, w.id)"
      />
      </div>
    </div>
  </div>
</template>

<style scoped>
.canvas-viewport {
  width: 100%;
  height: 100%;
  overflow: auto;
  display: flex;
  background: var(--color-bg);
  padding: 12px;
  box-sizing: border-box;
}
/*
 * 居中用 margin:auto 而不是 align/justify-content: center。
 *
 * flex 的 align-items/justify-content:center 在**内容溢出容器**时，会把溢出量
 * 平均分到两侧且不计入可滚动区域 —— 画布顶部/左侧被推到滚动区外，滚回去也够不着，
 * 表现就是「画布贴着右边缘被切掉一截」。margin:auto 溢出时算成 0，内容从头贴齐，
 * 整块都能滚到；不溢出时行为和 center 一致。
 *
 * 只在横向 auto（画布窄于中栏时居中好看），纵向顶对齐 —— 1920×1080 塞进中栏必然
 * 左右贴边、上下有余，垂直再居中会在顶部留一条很宽的空带（用户反馈的「空太多」）。
 */
.canvas-sizer {
  position: relative;
  flex: 0 0 auto;
  margin-right: auto;
}
.canvas-stage {
  position: absolute;
  top: 0;
  left: 0;
  transform-origin: top left;
  background: var(--bi-bg);
  overflow: hidden;
  box-sizing: border-box;
}
.canvas-bg {
  position: absolute;
  inset: 0;
  background: var(--bi-bg);
}
/*
 * 框选矩形。用 semi-transparent 而不是 outline —— 框本身要能看出
 * 「覆盖了哪些格子」，outline 画在边界上反而挡不住下层组件。
 * z-index 取 1：压在所有 widget（z+1，起始为 1）之上但低于选中框手柄。
 */
.select-band {
  position: absolute;
  border: 1px dashed var(--color-primary, #2563eb);
  background: color-mix(in srgb, var(--color-primary, #2563eb) 12%, transparent);
  pointer-events: none;
  z-index: 1;
}
</style>