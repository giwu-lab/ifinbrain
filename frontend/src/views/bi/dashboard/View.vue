<template>
  <div
    ref="root"
    class="bi-view"
    :class="{ 'no-chrome': hideChrome }"
    :style="themeStyle"
  >
    <!-- 缩放容器：把 1920×1080 基准画布等比映射到实际视口。
         注意必须用「缩放后的尺寸」做外层盒子来居中：transform 不改变布局尺寸，
         直接 flex 居中一个 1920px 宽的元素会算错偏移，内容被推到视口左侧之外。 -->
    <div class="stage-wrap">
      <div class="stage-sizer" :style="sizerStyle">
        <div class="stage" :style="stageStyle">
          <div ref="themeHost" class="stage-inner" :style="themeStyle">
            <BiWidgetFrame
              v-for="w in shown"
              :key="w.id"
              :widget="w"
              :theme="store.theme"
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

    <!-- 右上角悬浮控件 -->
    <transition name="fade">
      <div v-if="!hideChrome" class="ctrl-bar">
        <span class="ctrl-name">{{ meta.name }}</span>
        <span class="ctrl-clock tabular">{{ clock }}</span>

        <el-tooltip :content="t('bi.view.refresh')" placement="bottom">
          <el-button link :icon="Refresh" :loading="refreshing" @click="onRefresh" />
        </el-tooltip>
        <el-tooltip :content="t('bi.view.fullscreen')" placement="bottom">
          <el-button link :icon="FullScreen" @click="onFullscreen" />
        </el-tooltip>
        <el-tooltip :content="t('bi.view.hideChrome')" placement="bottom">
          <el-button link :icon="Hide" @click="hideChrome = true" />
        </el-tooltip>
        <el-tooltip :content="t('bi.view.backToEdit')" placement="bottom">
          <el-button link :icon="Edit" @click="router.push(`/bi/dashboard/design/${meta.id}`)" />
        </el-tooltip>
      </div>
    </transition>

    <!-- 隐藏态的唤出入口：鼠标靠右上角 -->
    <div v-if="hideChrome" class="chrome-hotzone" @click="hideChrome = false"></div>

    <!-- 联动筛选生效提示：不给出口的话，点了图之后画面变了却没有任何解释，
         用户第一反应是「页面坏了」。 -->
    <transition name="fade">
      <div v-if="!hideChrome && filterLabel" class="filter-bar">
        <el-icon><Filter /></el-icon>
        <span class="filter-text">{{ filterLabel }}</span>
        <el-tooltip :content="t('bi.view.clearFilter')" placement="bottom">
          <el-button link :icon="Close" @click="interaction.clearAll()" />
        </el-tooltip>
      </div>
    </transition>

    <div v-if="!meta.id && !loading" class="fatal">{{ t('bi.view.notFound') }}</div>
  </div>
</template>

<script setup lang="ts">
/**
 * 大屏播放页。
 * - scaleMode 控制适配方式（fit 等比 / fill 裁切 / stretch 拉伸）
 * - 刷新失败不清空画面（BiWidgetFrame 的 err-bar 保留上一帧 + 提示时间）
 * - ?full=1 隐藏全部控件，用于拼接屏；?p_<参数名>=值 透传给组件查询
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { Close, Edit, Filter, FullScreen, Hide, Refresh } from '@element-plus/icons-vue';
import { biApi, type BiDashboardDto, type BiWidget } from '@/api/bi';
import { useBiDesignStore } from '@/stores/biDesign';
import { useBiData } from '@/composables/useBiData';
import { useBiInteraction } from '@/composables/useBiInteraction';
import { gridRectPx, presetVars } from '@/utils/bi/layout';
import BiWidgetFrame from '@/components/bi/BiWidgetFrame.vue';
import dayjs from 'dayjs';

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const store = useBiDesignStore();
const { dataFor, load, refresh } = useBiData();

const root = ref<HTMLElement | null>(null);
const themeHost = ref<HTMLElement | null>(null);
const meta = ref<Pick<BiDashboardDto, 'id' | 'name'>>({ id: 0, name: '' });
const loading = ref(true);
const refreshing = ref(false);
const hideChrome = ref(false);
const clock = ref('');

const widgets = computed(() => store.orderedWidgets);
const interaction = useBiInteraction(widgets);

/**
 * 渲染与取数统一用这一份：URL 透传参数 + 联动筛选值，两层都只是覆盖 params，
 * 之后再交给 useBiData 按签名去重。两者互不干扰 —— URL 是「进入时的初始条件」，
 * 联动是「进来之后用户点的」，后者的优先级更高（写在后面）。
 */
const shown = computed<BiWidget[]>(() => {
  const url = urlParams();
  const eff = interaction.effective.value;
  if (!Object.keys(url).length) return eff;
  return eff.map((w) => {
    if (!w.data?.queryId) return w;
    return { ...w, data: { ...w.data, params: { ...url, ...(w.data.params || {}) } } };
  });
});

/** 「正在按 X 过滤」的文字。多个筛选用、分隔，最多显示 3 个避免撑爆工具条 */
const filterLabel = computed(() => {
  const list = interaction.activeFilters.value;
  if (!list.length) return '';
  const head = list.slice(0, 3).map((f) => `${f.param}=${f.value}`).join('、');
  return list.length > 3 ? `${head} …` : head;
});

/**
 * 视口尺寸做成响应式：scale 依赖它。
 * 直接在 computed 里读 window.innerWidth 不会触发重算 —— 按「全屏」后
 * 窗口尺寸变了但 computed 缓存没失效，画面比例停在旧值上。
 */
const viewport = ref({ w: window.innerWidth, h: window.innerHeight });
function onResize() {
  viewport.value = { w: window.innerWidth, h: window.innerHeight };
}

/** 视口可用尺寸 → 缩放比 */
const scale = computed(() => {
  const c = store.canvas;
  const vw = viewport.value.w;
  const vh = viewport.value.h;
  if (c.width <= 0 || c.height <= 0) return { sx: 1, sy: 1 };
  switch (c.scaleMode) {
    case 'stretch': return { sx: vw / c.width, sy: vh / c.height };
    case 'fill': {
      const s = Math.max(vw / c.width, vh / c.height);
      return { sx: s, sy: s };
    }
    default: {
      const s = Math.min(vw / c.width, vh / c.height);
      return { sx: s, sy: s };
    }
  }
});

/** 缩放后的实际占位尺寸 —— 用它做 flex 居中，transform 只负责缩放内容 */
const sizerStyle = computed(() => {
  const c = store.canvas;
  const { sx, sy } = scale.value;
  return {
    width: `${c.width * sx}px`,
    height: `${c.height * sy}px`,
  };
});

const stageStyle = computed(() => {
  const c = store.canvas;
  const { sx, sy } = scale.value;
  return {
    width: `${c.width}px`,
    height: `${c.height}px`,
    transform: `scale(${sx}, ${sy})`,
    transformOrigin: 'top left',
  };
});

/** 主题变量写到内层容器，供组件 + ChartBase 读取 */
const themeStyle = computed(() => presetVars(store.theme.preset, store.theme.accent));

function rectOf(w: BiWidget) {
  return gridRectPx(store.canvas, { x: w.x, y: w.y, w: w.w, h: w.h });
}

/** URL 参数透传：?p_区域=华东 → params.区域 */
function urlParams(): Record<string, string> {
  const q = route.query as Record<string, string | undefined>;
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(q)) {
    if (k.startsWith('p_') && typeof v === 'string') out[k.slice(2)] = v;
  }
  return out;
}

// ===================== 加载 / 刷新 =====================

let timer: number | null = null;

async function loadAll(force = false) {
  const list = shown.value;
  if (force) refreshing.value = true;
  await (force ? refresh(list) : load(list));
  refreshing.value = false;
}

/**
 * 点图表 → 联动筛选变化 → 按新 params 重新取数。
 * 用 refresh 忽略缓存新鲜度，避免「点到同值命中未过期缓存、画面不跟着变」的假象。
 */
watch(
  () => interaction.overrideSig.value,
  () => { loadAll(true); },
);

function setupAutoRefresh(sec: number) {
  if (timer) { clearInterval(timer); timer = null; }
  if (sec > 0) {
    timer = window.setInterval(() => loadAll(true), sec * 1000);
  }
}

async function onRefresh() {
  await loadAll(true);
  ElMessage.success(t('bi.view.refreshed'));
}

async function onFullscreen() {
  try {
    if (!document.fullscreenElement) await root.value?.requestFullscreen();
    else await document.exitFullscreen();
  } catch {
    ElMessage.warning(t('bi.view.fsFailed'));
  }
}

let clockTimer: number | null = null;

onMounted(async () => {
  const id = Number(route.params.id);
  if (!Number.isFinite(id) || id <= 0) {
    loading.value = false;
    return;
  }
  hideChrome.value = route.query.full === '1';
  try {
    const d = await biApi.detail(id);
    store.reset(d);
    meta.value = { id: d.id, name: d.name };
    store.clearDirty();
    setupAutoRefresh(d.refreshInterval || 0);
    await loadAll(false);
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('bi.view.loadFailed'));
  } finally {
    loading.value = false;
  }
  clockTimer = window.setInterval(() => {
    clock.value = dayjs().format('YYYY-MM-DD HH:mm:ss');
  }, 1000);
  window.addEventListener('resize', onResize);
});

watch(() => store.meta?.refreshInterval, (sec) => setupAutoRefresh(sec || 0));

onBeforeUnmount(() => {
  if (timer) clearInterval(timer);
  if (clockTimer) clearInterval(clockTimer);
  window.removeEventListener('resize', onResize);
  timer = null;
  clockTimer = null;
});
</script>

<style scoped>
.bi-view {
  position: fixed;
  inset: 0;
  overflow: hidden;
  background: var(--bi-bg, #0b1220);
}
.stage-wrap {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.stage-sizer {
  position: relative;
  flex: 0 0 auto;
}
.stage {
  position: absolute;
  top: 0;
  left: 0;
}
.stage-inner {
  position: relative;
  width: 100%;
  height: 100%;
  background: var(--bi-bg);
}
.ctrl-bar {
  position: fixed;
  top: 12px;
  right: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px;
  border-radius: 6px;
  background: color-mix(in srgb, var(--bi-panel) 88%, transparent);
  border: 1px solid var(--bi-border);
  backdrop-filter: blur(3px);
  z-index: 999;
}
.ctrl-name {
  font-size: 13px;
  color: var(--bi-text);
}
.ctrl-clock {
  font-size: 12px;
  color: var(--bi-text-muted);
}
/* 联动筛选提示：贴在右上角控件条下方，视觉上同属一组 */
.filter-bar {
  position: fixed;
  top: 48px;
  right: 14px;
  display: flex;
  align-items: center;
  gap: 6px;
  max-width: 320px;
  padding: 4px 6px 4px 10px;
  border-radius: 6px;
  background: color-mix(in srgb, var(--bi-panel) 88%, transparent);
  border: 1px solid var(--bi-border);
  backdrop-filter: blur(3px);
  color: var(--bi-text-secondary);
  z-index: 999;
}
.filter-text {
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chrome-hotzone {
  position: fixed;
  top: 0;
  right: 0;
  width: 90px;
  height: 46px;
  z-index: 998;
}
.fatal {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--bi-text-muted);
  font-size: 14px;
}
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>