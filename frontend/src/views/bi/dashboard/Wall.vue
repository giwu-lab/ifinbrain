<template>
  <div class="bi-wall">
    <div v-if="loading" class="wall-hint">{{ t('common.loading') }}</div>

    <!-- 空态 A：一个已发布的都没有 -->
    <div v-else-if="!published.length" class="wall-hint">{{ t('bi.wall.empty') }}</div>

    <!-- 空态 B：有已发布的，但一个都没加入展示墙 -->
    <div v-else-if="!boards.length" class="wall-hint wall-cta">
      <p class="cta-text">{{ t('bi.wall.noneSelected', { n: published.length }) }}</p>
      <el-button type="primary" :icon="Setting" @click="pickerVisible = true">
        {{ t('bi.wall.pick') }}
      </el-button>
    </div>

    <!-- 缩放容器：transform 不改变布局尺寸，直接 flex 居中一个 1920px 宽的盒子
         会把可见内容整体推出视口（表现是画面歪到右下角且被裁掉）。
         必须用「缩放后的尺寸」做居中盒子，stage 再以 top-left 为原点缩放。
         与 View.vue 的 stage-wrap / stage-sizer 同一套写法。 -->
    <div v-else class="wall-wrap">
      <div class="wall-sizer" :style="sizerStyle">
        <div class="wall-stage" :style="stageStyle">
          <div class="wall-inner" :style="themeStyle">
            <BiWidgetFrame
              v-for="w in widgets"
              :key="w.id"
              :widget="w"
              :theme="layout.theme"
              :loading="dataFor(w)?.loading ?? false"
              :error="dataFor(w)?.error ?? ''"
              :last-ok-at="dataFor(w)?.lastOkAt ?? 0"
              :data="dataFor(w)?.data ?? null"
              :left="rectOf(w).left"
              :top="rectOf(w).top"
              :width="rectOf(w).width"
              :height="rectOf(w).height"
              :z-index="w.z + 1"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- 底栏：只要有已发布大屏就渲染。
         原来写的是 boards.length > 1 —— 0/1 个时整条 bar 都不出现，
         结果「换一个」「挑内容」的入口一起消失，用户在墙上什么都做不了。 -->
    <div v-if="!loading && published.length" class="wall-bar">
      <span v-if="boards.length" class="wall-name">{{ activeName }}</span>
      <span v-if="boards.length > 1" class="dots">
        <button
          v-for="(b, i) in boards"
          :key="b.id"
          type="button"
          class="dot-btn"
          :class="{ on: i === idx }"
          :title="b.name"
          @click="go(i)"
        ></button>
      </span>
      <span v-if="boards.length" class="wall-count tabular">{{ idx + 1 }} / {{ boards.length }}</span>

      <el-tooltip :content="t('bi.wall.refresh')" placement="top">
        <el-button link :icon="Refresh" :loading="refreshing" :disabled="!boards.length" @click="onRefresh" />
      </el-tooltip>
      <el-tooltip :content="t('bi.wall.pick')" placement="top">
        <el-button link :icon="Setting" @click="pickerVisible = true" />
      </el-tooltip>
    </div>

    <!-- 选择面板：勾选哪些已发布大屏上墙，并调整轮播顺序。改动即时生效。 -->
    <el-dialog
      v-model="pickerVisible"
      :title="t('bi.wall.pickTitle')"
      width="520px"
      :append-to-body="true"
      align-center
    >
      <p class="pick-hint">{{ t('bi.wall.pickHint') }}</p>

      <section class="pick-sec">
        <h4 class="pick-sec-title">{{ t('bi.wall.order') }}</h4>
        <div class="pick-box">
          <div v-for="(id, i) in picked" :key="id" class="pick-row">
            <span class="ord tabular">{{ pad2(i + 1) }}</span>
            <span class="pick-name">{{ nameOf(id) }}</span>
            <span class="pick-ops">
              <el-tooltip :content="t('bi.wall.moveUp')" placement="top">
                <el-button link :icon="ArrowUp" :disabled="i === 0" @click="move(i, -1)" />
              </el-tooltip>
              <el-tooltip :content="t('bi.wall.moveDown')" placement="top">
                <el-button link :icon="ArrowDown" :disabled="i === picked.length - 1" @click="move(i, 1)" />
              </el-tooltip>
              <el-tooltip :content="t('bi.wall.remove')" placement="top">
                <el-button link :icon="Remove" @click="removePicked(id)" />
              </el-tooltip>
            </span>
          </div>
          <div v-if="!picked.length" class="pick-none">{{ t('bi.wall.pickedEmpty') }}</div>
        </div>
      </section>

      <section class="pick-sec">
        <h4 class="pick-sec-title">{{ t('bi.wall.unpicked') }}</h4>
        <div class="pick-box">
          <div v-for="b in unpicked" :key="b.id" class="pick-row">
            <span class="ord ghost">—</span>
            <span class="pick-name">{{ b.name }}</span>
            <span class="pick-ops">
              <el-tooltip :content="t('bi.wall.add')" placement="top">
                <el-button link :icon="Plus" @click="addPicked(b.id)" />
              </el-tooltip>
            </span>
          </div>
          <div v-if="!unpicked.length" class="pick-none">{{ t('bi.wall.allPicked') }}</div>
        </div>
      </section>

      <template #footer>
        <el-button @click="pickAll">{{ t('bi.wall.pickAll') }}</el-button>
        <el-button @click="picked = []; applyPicked()">{{ t('bi.wall.pickNone') }}</el-button>
        <el-button @click="resetPicked">{{ t('bi.wall.reset') }}</el-button>
        <el-button type="primary" @click="pickerVisible = false">{{ t('common.close') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * 展示墙：轮播「用户选定的那几个」已发布大屏。
 *
 * 三件事和之前不一样：
 * 1. 墙自己持有 layout（不再复用 biDesign store）。原来 store.reset() 会把设计器
 *    内存里的未保存改动一起冲掉，还顺手 dirty=false —— 用户看着像"保存成功了"。
 *    墙是只读播放面，没有任何理由碰设计器的编辑态。
 * 2. 可选择：勾选哪些已发布大屏上墙 + 调轮播顺序，选择落 localStorage（按用户分键）。
 *    也支持 ?ids=2,5 临时覆盖，便于把某一台拼接屏固定到指定组合（覆盖时不落盘）。
 * 3. 取数缓存跨切屏复用（useBiData 按 queryId+params 去重），轮播不额外打后端。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ArrowDown, ArrowUp, Plus, Refresh, Remove, Setting } from '@element-plus/icons-vue';
import { biApi, type BiDashboardDto, type BiWidget, type DashboardLayout } from '@/api/bi';
import { useAuthStore } from '@/stores/auth';
import { useBiData } from '@/composables/useBiData';
import { defaultLayout, gridRectPx, migrateLayout, presetVars } from '@/utils/bi/layout';
import BiWidgetFrame from '@/components/bi/BiWidgetFrame.vue';

const { t } = useI18n();
const route = useRoute();
const auth = useAuthStore();
const { dataFor, load, refresh } = useBiData();

/** 底部控制条预留高度（bar 高约 30px + bottom 偏移 14px） */
const BAR_RESERVE = 56;

const published = ref<BiDashboardDto[]>([]);
const picked = ref<number[]>([]);
const layout = ref<DashboardLayout>(defaultLayout());
const idx = ref(0);
const loading = ref(true);
const refreshing = ref(false);
const pickerVisible = ref(false);

const publishedMap = computed(() => new Map(published.value.map((b) => [b.id, b])));

/** 展示中的大屏，按 picked 的数组顺序轮播 */
const boards = computed<BiDashboardDto[]>(() =>
  picked.value
    .map((id) => publishedMap.value.get(id))
    .filter((b): b is BiDashboardDto => !!b),
);

const unpicked = computed(() => published.value.filter((b) => !picked.value.includes(b.id)));

const active = computed(() => boards.value[idx.value] ?? null);
const activeName = computed(() => active.value?.name ?? '');

const widgets = computed<BiWidget[]>(() =>
  [...layout.value.widgets].filter((w) => !w.hidden).sort((a, b) => a.z - b.z),
);

const themeStyle = computed(() => presetVars(layout.value.theme.preset, layout.value.theme.accent));

function rectOf(w: BiWidget) {
  return gridRectPx(layout.value.canvas, { x: w.x, y: w.y, w: w.w, h: w.h });
}

/**
 * 视口尺寸做成响应式：scale 依赖它。
 * 直接在 computed 里读 window.innerWidth 是不行的 —— 那个值不是响应式的，
 * 窗口全屏 / 改尺寸后 computed 不会重算，画面比例就停在旧值上了。
 */
const viewport = ref({ w: window.innerWidth, h: window.innerHeight });
function syncViewport() {
  viewport.value = { w: window.innerWidth, h: window.innerHeight };
}

const scale = computed(() => {
  const c = layout.value.canvas;
  if (c.width <= 0 || c.height <= 0) return 1;
  const vw = viewport.value.w;
  const vh = viewport.value.h - BAR_RESERVE;
  switch (c.scaleMode) {
    case 'stretch':
      return vw / c.width;
    case 'fill':
      return Math.max(vw / c.width, vh / c.height);
    default:
      return Math.min(vw / c.width, vh / c.height);
  }
});

/** 缩放后的实际占位尺寸 —— 用它做 flex 居中，transform 只负责缩放内容 */
const sizerStyle = computed(() => {
  const c = layout.value.canvas;
  const s = scale.value;
  return { width: `${c.width * s}px`, height: `${c.height * s}px` };
});

const stageStyle = computed(() => {
  const c = layout.value.canvas;
  return {
    width: `${c.width}px`,
    height: `${c.height}px`,
    transform: `scale(${scale.value})`,
    transformOrigin: 'top left',
  };
});

function nameOf(id: number) {
  return publishedMap.value.get(id)?.name || `#${id}`;
}

function pad2(n: number) {
  return String(n).padStart(2, '0');
}

// ===================== 选择持久化 =====================

const storeKey = computed(() => `ifinbrain.bi.wall.v1:${auth.user?.id ?? 'anon'}`);

/** ?ids=2,5 —— 覆盖用（拼接屏固定组合），不落 localStorage */
function idsFromQuery(): number[] | null {
  const raw = route.query.ids;
  if (typeof raw !== 'string' || !raw.trim()) return null;
  const ids = raw.split(',').map((s) => Number(s.trim())).filter((n) => Number.isFinite(n) && n > 0);
  return ids.length ? ids : null;
}

/**
 * 初始选中集合。
 * - ?ids= 优先（临时覆盖）
 * - localStorage 有记录就用记录里的（**包括空数组**：用户显式清空过，
 *   不该被"聪明地"恢复成全选；空态由 noneSelected + CTA 接住）
 * - 两者都没有 → 首次进入，默认全部已发布大屏
 */
function initialPicked(): number[] {
  const fromUrl = idsFromQuery();
  if (fromUrl) return fromUrl.filter((id) => publishedMap.value.has(id));
  const raw = localStorage.getItem(storeKey.value);
  if (raw != null) {
    try {
      const arr = JSON.parse(raw);
      if (Array.isArray(arr)) {
        const ids = arr.filter((n: unknown): n is number => Number.isFinite(Number(n))).map(Number);
        // 去重 + 丢掉已下架/已删除的（保序）
        return [...new Set(ids)].filter((id) => publishedMap.value.has(id));
      }
    } catch {
      /* 落盘数据损坏 → 回落默认全选 */
    }
  }
  return published.value.map((b) => b.id);
}

function persistPicked() {
  // ?ids= 覆盖态不落盘，避免把一次性配置固化成用户偏好
  if (idsFromQuery()) return;
  try {
    localStorage.setItem(storeKey.value, JSON.stringify(picked.value));
  } catch {
    /* 隐私模式 / 配额满：降级为仅本次会话有效 */
  }
}

// ===================== 选择操作 =====================

function applyPicked() {
  if (!picked.value.includes(active.value?.id ?? -1)) idx.value = 0;
  persistPicked();
  switchTo();
}

function addPicked(id: number) {
  if (picked.value.includes(id)) return;
  picked.value = [...picked.value, id];
  applyPicked();
}

function removePicked(id: number) {
  picked.value = picked.value.filter((x) => x !== id);
  applyPicked();
}

function move(i: number, dir: 1 | -1) {
  const next = [...picked.value];
  const j = i + dir;
  if (j < 0 || j >= next.length) return;
  [next[i], next[j]] = [next[j], next[i]];
  picked.value = next;
  applyPicked();
}

function pickAll() {
  picked.value = published.value.map((b) => b.id);
  applyPicked();
}

/** 「恢复」= 回到默认（全部已发布，按 sort 排序） */
function resetPicked() {
  picked.value = published.value.map((b) => b.id);
  applyPicked();
}

// ===================== 轮播 =====================

let timer: number | null = null;

/** 间隔按「当前屏」的 refreshInterval 算，切屏后重算（原来只在挂载时算一次） */
function setupRotation() {
  if (timer) { clearInterval(timer); timer = null; }
  if (boards.value.length < 2) return;
  const sec = Math.max(10, (active.value?.refreshInterval || 0) * 3);
  timer = window.setInterval(() => go(idx.value + 1), sec * 1000);
}

function go(i: number) {
  const n = boards.value.length;
  if (!n) return;
  idx.value = ((i % n) + n) % n;
  switchTo();
}

function switchTo() {
  const b = active.value;
  if (!b) {
    layout.value = defaultLayout();
    if (timer) { clearInterval(timer); timer = null; }
    return;
  }
  const base = migrateLayout(b.layout);
  if (b.theme) base.theme = { ...base.theme, ...b.theme };
  layout.value = base;
  load(widgets.value);
  setupRotation();
}

async function onRefresh() {
  refreshing.value = true;
  await refresh(widgets.value);
  refreshing.value = false;
}

onMounted(async () => {
  window.addEventListener('resize', syncViewport);
  try {
    const all = await biApi.list();
    published.value = all
      .filter((b) => b.status === 1)
      .sort((a, b) => (a.sort - b.sort) || (a.id - b.id));
    picked.value = initialPicked();
    if (boards.value.length) switchTo();
  } finally {
    loading.value = false;
  }
});

onBeforeUnmount(() => {
  if (timer) clearInterval(timer);
  timer = null;
  window.removeEventListener('resize', syncViewport);
});
</script>

<style scoped>
.bi-wall {
  position: fixed;
  inset: 0;
  overflow: hidden;
  background: var(--bi-bg, #0b1220);
  display: flex;
  align-items: center;
  justify-content: center;
}
.wall-hint {
  color: var(--bi-text-muted, #7e88bd);
  font-size: 14px;
}
.wall-cta {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
}
.cta-text {
  margin: 0;
}
.wall-stage {
  position: absolute;
  top: 0;
  left: 0;
  transform-origin: top left;
}
.wall-wrap {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}
.wall-sizer {
  position: relative;
  flex: 0 0 auto;
  pointer-events: auto;
}
.wall-inner {
  position: relative;
  width: 100%;
  height: 100%;
  background: var(--bi-bg);
}
.wall-bar {
  position: fixed;
  left: 50%;
  bottom: 14px;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 5px 12px;
  border-radius: 6px;
  background: color-mix(in srgb, var(--bi-panel) 88%, transparent);
  border: 1px solid var(--bi-border);
  backdrop-filter: blur(3px);
}
.wall-name {
  font-size: 13px;
  color: var(--bi-text);
}
.dots {
  display: flex;
  gap: 6px;
}
.dot-btn {
  width: 7px;
  height: 7px;
  padding: 0;
  border-radius: 50%;
  border: 1px solid var(--bi-text-muted);
  background: transparent;
  cursor: pointer;
}
.dot-btn.on {
  background: var(--bi-accent);
  border-color: var(--bi-accent);
}
.wall-count {
  font-size: 12px;
  color: var(--bi-text-muted);
}

/* ===================== 选择面板 ===================== */
/* 跟项目一致：不套 el-card，用 1px 边框的 plain div；数字序只用在这里 ——
   轮播顺序本身就是真正有序的步骤，符合 AGENTS §7 的数字序用法。 */
.pick-hint {
  margin: 0 0 14px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.pick-sec + .pick-sec {
  margin-top: 16px;
}
.pick-sec-title {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
}
.pick-box {
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  max-height: 208px;
  overflow-y: auto;
}
.pick-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 10px;
  min-height: 40px;
}
.pick-row + .pick-row {
  border-top: 1px solid var(--el-border-color-lighter);
}
.pick-row:hover {
  background: var(--el-fill-color-light);
}
.ord {
  flex: 0 0 20px;
  font-size: 12px;
  color: var(--color-primary, #2563eb);
}
.ord.ghost {
  color: var(--el-text-color-placeholder);
}
.pick-name {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 13px;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pick-ops {
  flex: 0 0 auto;
  display: flex;
  gap: 2px;
}
.pick-none {
  padding: 14px 10px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
</style>