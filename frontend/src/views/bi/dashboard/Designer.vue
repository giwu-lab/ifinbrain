<template>
  <div ref="rootEl" class="bi-designer">
    <!-- ============ 顶栏 ============ -->
    <div class="designer-topbar">
      <div class="topbar-left">
        <el-button link :icon="ArrowLeft" @click="goBack">{{ t('bi.dash.backToList') }}</el-button>
        <span class="banner" />
        <span class="designer-title">{{ meta.name || t('bi.dash.untitled') }}</span>
        <code v-if="meta.code" class="designer-code">{{ meta.code }}</code>
        <span v-if="store.dirty" class="dirty-mark" :title="t('bi.dash.unsavedHint')">●</span>
      </div>

      <div class="topbar-center">
        <el-radio-group v-model="mode" size="small" class="mode-switch">
          <el-radio-button label="build">{{ t('bi.dash.mode.build') }}</el-radio-button>
          <el-radio-button label="preview">{{ t('bi.dash.mode.preview') }}</el-radio-button>
          <el-radio-button label="json">{{ t('bi.dash.mode.json') }}</el-radio-button>
        </el-radio-group>
      </div>

      <div class="topbar-right">
        <el-tooltip :content="t('bi.dash.undo')" placement="bottom">
          <el-button link :icon="RefreshLeft" :disabled="!store.canUndo" @click="store.undo" />
        </el-tooltip>
        <el-tooltip :content="t('bi.dash.redo')" placement="bottom">
          <el-button link :icon="RefreshRight" :disabled="!store.canRedo" @click="store.redo" />
        </el-tooltip>

        <el-divider direction="vertical" />

        <template v-if="mode !== 'json'">
          <el-select
            v-model="themePreset"
            size="small"
            class="theme-select"
            @change="onThemeChange"
          >
            <el-option
              v-for="p in THEME_KEYS"
              :key="p"
              :label="t(`bi.theme.${p}`)"
              :value="p"
            />
          </el-select>

          <el-tooltip :content="t('bi.dash.zoom')" placement="bottom">
            <el-dropdown @command="onZoom" trigger="click">
              <el-button link>{{ zoomLabel }}</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item
                    v-for="z in ZOOM_ITEMS"
                    :key="String(z.value)"
                    :command="z.value"
                  >{{ z.value === 'fit' ? t(z.label) : z.label }}</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </el-tooltip>
        </template>

        <el-divider direction="vertical" />

        <!-- 多选对齐：只在选中 ≥2 个组件时出现（AGENTS §7：状态在布局中表达，
             这里用一排图标按钮而不是文字下拉 —— 高频操作直接可点） -->
        <template v-if="mode === 'build' && store.selectedIds.length >= 2">
          <span class="align-group">
            <span class="align-count">{{ t('bi.dash.selectedN', { n: store.selectedIds.length }) }}</span>
            <el-tooltip v-for="a in ALIGN_ITEMS" :key="a.op" :content="t(a.labelKey)" placement="bottom">
              <el-button link :icon="a.icon" @click="store.align(a.op)" />
            </el-tooltip>
            <el-tooltip
              :content="t('bi.dash.distributeH')"
              placement="bottom"
            >
              <el-button
                link :icon="Sort"
                :disabled="store.selectedIds.length < 3"
                @click="store.distribute('horizontal')"
              />
            </el-tooltip>
            <el-tooltip :content="t('bi.dash.distributeV')" placement="bottom">
              <el-button
                link :icon="DCaret"
                :disabled="store.selectedIds.length < 3"
                @click="store.distribute('vertical')"
              />
            </el-tooltip>
          </span>

          <el-divider direction="vertical" />
        </template>

        <el-button :icon="View" @click="onViewCurrent">{{ t('bi.dash.openView') }}</el-button>
        <el-button type="primary" :icon="Document" :loading="saving" @click="onSave">
          {{ t('common.save') }}
        </el-button>
        <el-button
          v-if="meta.id && auth.hasPerm('bi:dashboard:publish')"
          :icon="Promotion"
          :loading="publishing"
          @click="onPublish"
        >{{ meta.status === 1 ? t('bi.dash.unpublish') : t('bi.dash.publish') }}</el-button>
      </div>
    </div>

    <!-- ============ 主体 ============ -->
    <div class="designer-body">
      <!-- 左：组件库 / 图层 -->
      <aside class="pane pane-left">
        <el-tabs v-model="leftTab" class="pane-tabs">
          <el-tab-pane :label="t('bi.palette.title')" name="palette">
            <BiWidgetPalette @add="onAdd" />
          </el-tab-pane>
          <el-tab-pane :label="t('bi.layers.title')" name="layers">
            <BiLayerList
              :widgets="store.orderedWidgets"
              :selected-id="store.selectedId"
              @select="store.select"
              @patch="onLayerPatch"
              @move="store.moveLayer"
              @remove="onLayerRemove"
            />
          </el-tab-pane>
          <el-tab-pane :label="t('bi.dash.settings')" name="meta">
            <div class="meta-pane">
              <el-form label-width="96px" size="small" label-position="left">
                <el-form-item :label="t('bi.dash.name')">
                  <el-input v-model="meta.name" />
                </el-form-item>
                <el-form-item :label="t('bi.dash.description')">
                  <el-input v-model="meta.description" type="textarea" :rows="2" />
                </el-form-item>
                <el-form-item :label="t('bi.dash.code')">
                  <el-input v-model="meta.code" :disabled="!!meta.id" />
                </el-form-item>
                <el-form-item :label="t('bi.dash.width')">
                  <el-input-number
                    v-model="canvasWidth" :min="640" :max="7680" :step="80"
                    controls-position="right" style="width: 100%"
                    @change="onCanvasChange"
                  />
                </el-form-item>
                <el-form-item :label="t('bi.dash.height')">
                  <el-input-number
                    v-model="canvasHeight" :min="360" :max="4320" :step="60"
                    controls-position="right" style="width: 100%"
                    @change="onCanvasChange"
                  />
                </el-form-item>
                <el-form-item :label="t('bi.dash.grid')">
                  <div class="grid-row">
                    <el-input-number
                      v-model="gridCols" :min="4" :max="48" :step="1"
                      controls-position="right"
                      @change="onCanvasChange"
                    />
                    <span class="x">×</span>
                    <el-input-number
                      v-model="gridRows" :min="2" :max="36" :step="1"
                      controls-position="right"
                      @change="onCanvasChange"
                    />
                  </div>
                </el-form-item>
                <el-form-item :label="t('bi.dash.refreshInterval')">
                  <el-input-number
                    v-model="refreshInterval" :min="0" :max="3600" :step="10"
                    controls-position="right" style="width: 100%"
                  />
                  <div class="field-hint">{{ t('bi.dash.refreshHint') }}</div>
                </el-form-item>
              </el-form>
            </div>
          </el-tab-pane>
        </el-tabs>
      </aside>

      <!-- 中：画布 -->
      <main class="pane pane-center">
        <div v-if="mode === 'json'" class="json-pane">
          <el-input
            v-model="jsonText"
            type="textarea"
            :rows="30"
            class="json-input"
            spellcheck="false"
          />
          <div class="json-actions">
            <el-button @click="onFormatJson">{{ t('bi.dash.format') }}</el-button>
            <el-button type="primary" @click="onApplyJson">{{ t('bi.dash.applyJson') }}</el-button>
          </div>
          <div v-if="jsonErrors.length" class="json-errors">
            <div v-for="(e, i) in jsonErrors" :key="i">{{ e }}</div>
          </div>
        </div>

        <BiCanvas
          v-else
          :canvas="store.canvas"
          :widgets="store.orderedWidgets"
          :theme="store.theme"
          :selected-id="store.selectedId"
          :editable="mode === 'build'"
          :show-grid="mode === 'build'"
          :zoom="effectiveZoom"
          @available="onAvailable"
          :data-for="dataForEffective"
          :on-select="(id) => store.select(id)"
          :on-toggle-select="store.toggleSelect"
          :on-set-selection="store.setSelection"
          :selected-ids="store.selectedIds"
          :on-move="onCanvasMove"
          :on-commit="store.commitGeometry"
        />
      </main>

      <!-- 右：属性 -->
      <aside class="pane pane-right">
        <BiPropsPanel
          :widget="store.selectedWidget"
          :canvas-cols="store.canvas.gridCols"
          :canvas-rows="store.canvas.gridRows"
          :widgets="store.orderedWidgets"
          :active-filters="activeFilters"
          @patch="onPatch"
          @style="onStyle"
          @extra="onExtra"
          @layer="onLayerOp"
          @clear-filters="onClearFilters"
        />
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import {
  computed, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, reactive, ref, watch,
  type Component,
} from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import {
  ArrowLeft, Bottom, DArrowLeft, DArrowRight, DCaret, Document,
  Promotion, RefreshLeft, RefreshRight, Sort, Top, View,
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { useBiDesignStore, type BiAlignOp } from '@/stores/biDesign';
import { useBiData } from '@/composables/useBiData';
import { useBiInteraction } from '@/composables/useBiInteraction';
import { biApi, type BiWidget, type DashboardLayout, type ThemePreset } from '@/api/bi';
import { THEME_PRESETS, validateLayout } from '@/utils/bi/layout';
import BiCanvas from '@/components/bi/BiCanvas.vue';
import BiWidgetPalette from '@/components/bi/panels/BiWidgetPalette.vue';
import BiLayerList from '@/components/bi/panels/BiLayerList.vue';
import BiPropsPanel from '@/components/bi/panels/BiPropsPanel.vue';

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const store = useBiDesignStore();
const data = useBiData();
const interaction = useBiInteraction(() => store.orderedWidgets);

/**
 * 提到顶层绑定：模板只会自动解包**顶层** setup 绑定的 ref，
 * `interaction.activeFilters` 是对象里的 ComputedRef，模板里不解包，
 * 传给 prop 的是一个 ComputedRef 对象而不是数组。
 */
const activeFilters = interaction.activeFilters;

function onClearFilters() {
  interaction.clearAll();
  data.refresh(interaction.effective.value);
}

/**
 * 画布传进来的是 store 里的原始 widget，但取数要按「合并了联动值」的副本算签名。
 *
 * 为什么不直接把 effective 传给 BiCanvas 的 :widgets：那份数组里对有筛选的
 * 组件是**新对象**，而 BiCanvas 的拖拽 / 缩放 / 锁定判断全都读 widget 本体，
 * 混进副本迟早出现「拖的是副本、存的是原对象」这类幽灵 bug。
 * 这里只在查数据时按 id 换一次，渲染与手势继续用原对象。
 */
const effById = computed(() => new Map(interaction.effective.value.map((w) => [w.id, w])));
function dataForEffective(w: BiWidget) {
  return data.dataFor(effById.value.get(w.id) || w);
}

const THEME_KEYS = Object.keys(THEME_PRESETS) as ThemePreset[];

/** 六个对齐基准，配图标 + i18n key */
const ALIGN_ITEMS: { op: BiAlignOp; icon: Component; labelKey: string }[] = [
  { op: 'left', icon: DArrowLeft, labelKey: 'bi.dash.alignLeft' },
  { op: 'centerX', icon: DCaret, labelKey: 'bi.dash.alignCenterX' },
  { op: 'right', icon: DArrowRight, labelKey: 'bi.dash.alignRight' },
  { op: 'top', icon: Top, labelKey: 'bi.dash.alignTop' },
  { op: 'centerY', icon: DCaret, labelKey: 'bi.dash.alignCenterY' },
  { op: 'bottom', icon: Bottom, labelKey: 'bi.dash.alignBottom' },
];

const leftTab = ref<'palette' | 'layers' | 'meta'>('palette');
const mode = computed({
  get: () => store.mode,
  set: (v: any) => store.setMode(v),
});
const saving = ref(false);
const publishing = ref(false);
const jsonText = ref('');
const jsonErrors = ref<string[]>([]);

/**
 * 缩放模式：'fit' = 适应窗口（自动把整个画布铺满中栏），数字 = 固定倍率。
 * 1920×1080 画布在中栏（~1200px）放不下，固定 100% 必然被裁 —— 默认就该是「适应」。
 */
type ZoomMode = 'fit' | number;
const zoomMode = ref<ZoomMode>('fit');
const rootEl = ref<HTMLElement | null>(null);

/** BiCanvas 上报的可用区（已扣掉画布容器 padding 的内容盒） */
const avail = ref({ w: 1200, h: 700 });

function onAvailable(box: { w: number; h: number }) {
  avail.value = box;
}

/**
 * 设计器嵌在 BasicLayout 的 el-main 里，上面已经压了「应用顶栏 + 顶部 tab 条」。
 * 写 `height: 100vh` 会比可视区高出那一截：.main 冒出竖向滚动条、画布底部被切，
 * fit 于是算出一个放不下的比例。
 *
 * 但靠 `calc(100vh - 顶栏高度)` 去减是脆的 —— 顶栏高度随 tab 条显隐、四种布局、
 * 面包屑换行都会变。改成把宿主（.main-inner）临时变成「定高的 flex 列」：
 *
 *   .layout-root(100vh) → el-main(定高) → .main-inner(补 height/display) → TabBar + .bi-designer
 *
 * 注意 .main-inner 里除了设计器还有顶部 TabBar，所以不能简单给设计器 `height:100%`
 * —— 那会让总高变成 100% + tab 条高度，.main 照样溢出。flex 列 + flex:1 让设计器
 * 自动吃掉剩余高度，不需要知道 tab 条具体多高。
 *
 * 宿主是 4 个 layout 共用的元素，所以只在设计器活着时临时改 inline style，
 * 切走 / 卸载时逐条还原，不影响其它页面。
 */
let hostEl: HTMLElement | null = null;
let hostPrev = { height: '', display: '', flexDirection: '' };

function lockHost() {
  const el = rootEl.value?.parentElement as HTMLElement | null;
  if (!el || el === hostEl) return;
  unlockHost();
  hostEl = el;
  hostPrev = { height: el.style.height, display: el.style.display, flexDirection: el.style.flexDirection };
  el.style.height = '100%';
  el.style.display = 'flex';
  el.style.flexDirection = 'column';
}

function unlockHost() {
  if (!hostEl) return;
  hostEl.style.height = hostPrev.height;
  hostEl.style.display = hostPrev.display;
  hostEl.style.flexDirection = hostPrev.flexDirection;
  hostEl = null;
}

/** 实际下发给 BiCanvas 的缩放比 */
const effectiveZoom = computed(() => {
  if (zoomMode.value !== 'fit') return zoomMode.value;
  const c = store.canvas;
  if (!c.width || !c.height || avail.value.w <= 0 || avail.value.h <= 0) return 1;
  const s = Math.min(avail.value.w / c.width, avail.value.h / c.height);
  // 不放大超过 1.5 倍，避免小画布被撑得过大失真
  return Math.max(0.1, Math.min(1.5, s));
});

const zoomLabel = computed(() =>
  zoomMode.value === 'fit'
    ? t('bi.dash.zoomFit', { n: Math.round(effectiveZoom.value * 100) })
    : `${Math.round(effectiveZoom.value * 100)}%`,
);

const ZOOM_ITEMS: { label: string; value: ZoomMode }[] = [
  { label: 'bi.dash.zoomFit', value: 'fit' },
  { label: '25%', value: 0.25 },
  { label: '50%', value: 0.5 },
  { label: '75%', value: 0.75 },
  { label: '100%', value: 1 },
  { label: '125%', value: 1.25 },
  { label: '150%', value: 1.5 },
];

/** 元信息独立于 layout，单独 reactive 存 */
const meta = reactive({
  id: 0,
  code: '',
  name: '',
  description: '',
  status: 0,
  sort: 0,
});

const canvasWidth = ref(1920);
const canvasHeight = ref(1080);
const gridCols = ref(24);
const gridRows = ref(12);
const refreshInterval = ref(0);
const themePreset = ref<ThemePreset>('default');

function syncFormFromState() {
  canvasWidth.value = store.canvas.width;
  canvasHeight.value = store.canvas.height;
  gridCols.value = store.canvas.gridCols;
  gridRows.value = store.canvas.gridRows;
  themePreset.value = store.theme.preset;
}

function onThemeChange(v: ThemePreset) {
  store.updateTheme({ preset: v });
}

function onCanvasChange() {
  store.updateCanvas({
    width: canvasWidth.value,
    height: canvasHeight.value,
    gridCols: gridCols.value,
    gridRows: gridRows.value,
  });
}

function onZoom(cmd: ZoomMode) {
  zoomMode.value = cmd;
  // 固定倍率时同步回 store，保持与既有缩放状态一致
  if (cmd !== 'fit') store.setZoom(cmd);
}

// ===================== 组件操作 =====================

function onAdd(type: any) {
  store.addWidget(type);
}

/** 拖拽 / 缩放过程：只改内存不压栈 */
function onCanvasMove(id: string, patch: Partial<BiWidget>) {
  store.updateWidget(id, patch, false);
}

function onPatch(patch: Partial<BiWidget>) {
  if (!store.selectedId) return;
  store.updateWidget(store.selectedId, patch);
}

function onStyle(patch: any) {
  if (!store.selectedId) return;
  const w = store.selectedWidget;
  store.updateWidget(store.selectedId, { style: { ...w!.style, ...patch } });
}

function onExtra(patch: Record<string, any>) {
  if (!store.selectedId) return;
  store.updateWidget(store.selectedId, { extra: { ...store.selectedWidget?.extra, ...patch } });
}

function onLayerOp(op: 'front' | 'back') {
  if (!store.selectedId) return;
  op === 'front' ? store.bringToFront(store.selectedId) : store.sendToBack(store.selectedId);
}

function onLayerPatch(id: string, patch: Partial<BiWidget>) {
  store.updateWidget(id, patch);
}

function onLayerRemove(id: string) {
  store.removeWidget(id);
}

// ===================== 生命周期 =====================

const isNew = computed(() => !meta.id);

onMounted(async () => {
  // 严格校验 id：只接受正整数。
  // 历史 bug：菜单 path 写成了字面量 '/bi/dashboard/design/:id'，点进去
  // route.params.id === ':id'，Number(':id') = NaN，前端就打 /api/bi/dashboard/NaN
  // → 后端 MethodArgumentTypeMismatchException → 500。非法 id 一律当"新建"处理。
  const raw = route.params.id;
  const id = Number(raw);
  const validId = Array.isArray(raw) ? NaN : id;
  const usable = Number.isInteger(validId) && validId > 0 ? validId : null;

  if (usable) {
    store.setLoading(true);
    try {
      // 设计器走 /design：要求实体级 design 权限，光有 view 的人打不开设计器
      const d = await biApi.detailForDesign(usable);
      store.reset(d);
      Object.assign(meta, {
        id: d.id, code: d.code, name: d.name, description: d.description || '',
        status: d.status, sort: d.sort,
      });
      refreshInterval.value = d.refreshInterval || 0;
      syncFormFromState();
    } catch {
      // 大屏不存在 / 无权限：回退到新建态，不要把错误页留给用户
      store.reset();
      meta.code = `bi_${Date.now().toString(36)}`;
      meta.name = '';
      syncFormFromState();
      ElMessage.warning(t('bi.dash.notFound'));
    } finally {
      store.setLoading(false);
    }
  } else {
    store.reset();
    meta.code = `bi_${Date.now().toString(36)}`;
    meta.name = '';
    syncFormFromState();
  }
  // 画布出现后立刻拉一次数据，实现「绑定即预览」
  await nextTick();
  data.load(store.orderedWidgets);
});

/** 组件数据配置变化 → 重新取数（预览用） */
watch(
  () => JSON.stringify(store.orderedWidgets.map((w) => ({ id: w.id, d: w.data }))),
  () => { data.load(store.orderedWidgets); },
);

/**
 * 联动筛选值变化 → 按新 params 重新取数。
 * 用 refresh（忽略新鲜度）而不是 load：点回同一个分类时值没变不会触发，
 * 但从 A 切到 B 时新签名没有缓存槽，load 也够用；refresh 更保险，
 * 避免「刚好命中未过期缓存 → 画面不跟着变」这种看起来像联动失灵的假象。
 */
watch(
  () => interaction.overrideSig.value,
  () => { data.refresh(interaction.effective.value); },
);

function goBack() {
  router.push('/bi/dashboard');
}

async function onViewCurrent() {
  // 播放页只读库里的数据。没落库就进去 = 看到旧主题 / 旧布局，
  // 用户会在播放页里怀疑「主题没生效」。所以进播放前先把改动存掉。
  const needSave = !meta.id || store.dirty;
  if (needSave && !(await onSave())) return;
  if (needSave && meta.id) ElMessage.success(t('bi.dash.autosavedOnPlay'));
  router.push(`/bi/dashboard/view/${meta.id}`);
}

// ===================== 保存 / 发布 =====================

/** @returns 是否保存成功（失败时调用方不要继续往下走） */
async function onSave(): Promise<boolean> {
  // 快照与校验单独兜一层：这两步一旦抛异常，「保存」会静默失败，
  // 用户只看到「改了没生效」，看不到任何报错。
  let layout: DashboardLayout;
  try {
    layout = store.toLayout();
  } catch (e: any) {
    ElMessage.error(t('bi.dash.snapshotFailed', { msg: e?.message || String(e) }));
    return false;
  }
  const v = validateLayout(layout);
  if (!v.ok) {
    ElMessage.error(v.errors[0]);
    return false;
  }
  saving.value = true;
  try {
    const body = {
      name: meta.name,
      description: meta.description,
      layout,
      theme: store.theme,
      refreshInterval: refreshInterval.value,
    };
    if (isNew.value) {
      const created = await biApi.create({ code: meta.code, ...body });
      meta.id = created.id;
      meta.code = created.code;
      meta.status = created.status;
      router.replace(`/bi/dashboard/design/${created.id}`);
    } else {
      await biApi.update(meta.id, body);
    }
    store.clearDirty();
    ElMessage.success(t('bi.dash.saved'));
    return true;
  } finally {
    saving.value = false;
  }
}

async function onPublish() {
  if (!meta.id && !(await onSave())) return;
  publishing.value = true;
  try {
    const d = meta.status === 1 ? await biApi.unpublish(meta.id) : await biApi.publish(meta.id);
    meta.status = d.status;
    ElMessage.success(t(d.status === 1 ? 'bi.dash.published2' : 'bi.dash.unpublished2'));
  } finally {
    publishing.value = false;
  }
}

// ===================== JSON 模式 =====================

function onFormatJson() {
  jsonText.value = JSON.stringify(store.toLayout(), null, 2);
  jsonErrors.value = [];
}

function onApplyJson() {
  try {
    const parsed = JSON.parse(jsonText.value);
    const r = store.applyLayoutJson(parsed);
    if (!r.ok) {
      jsonErrors.value = r.errors;
      return;
    }
    syncFormFromState();
    jsonErrors.value = [];
    ElMessage.success(t('bi.dash.jsonApplied'));
  } catch (e: any) {
    jsonErrors.value = [e?.message || String(e)];
  }
}

watch(mode, (m) => { if (m === 'json') onFormatJson(); });

// ===================== 快捷键 =====================

function onKey(e: KeyboardEvent) {
  const ae = document.activeElement;
  if (ae && ['INPUT', 'TEXTAREA'].includes(ae.tagName)) return;
  const ctrl = e.ctrlKey || e.metaKey;

  if (ctrl && e.key.toLowerCase() === 's') { e.preventDefault(); onSave(); return; }
  if (ctrl && e.key.toLowerCase() === 'z' && !e.shiftKey) { e.preventDefault(); store.undo(); return; }
  if (ctrl && (e.key.toLowerCase() === 'y' || (e.key.toLowerCase() === 'z' && e.shiftKey))) {
    e.preventDefault(); store.redo(); return;
  }
  if (ctrl && e.key.toLowerCase() === 'd' && store.selectedId) {
    e.preventDefault(); store.duplicateWidget(store.selectedId); return;
  }
  if (e.key === 'Delete' || e.key === 'Backspace') {
    if (store.selectedId) { e.preventDefault(); store.removeWidget(store.selectedId); }
    return;
  }
  if (e.key === 'Escape') { store.select(null); return; }
  if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.key) && store.selectedId) {
    e.preventDefault();
    const step = e.shiftKey ? 4 : 1;
    const w = store.selectedWidget!;
    const patch: any = {};
    if (e.key === 'ArrowUp') patch.y = w.y - step;
    if (e.key === 'ArrowDown') patch.y = w.y + step;
    if (e.key === 'ArrowLeft') patch.x = w.x - step;
    if (e.key === 'ArrowRight') patch.x = w.x + step;
    store.updateWidget(w.id, patch);
  }
}

onMounted(() => {
  bindKey();
  lockHost();
});

/**
 * 设计器在 BasicLayout 里是 keep-alive 的 tab —— 切走只触发 onDeactivated，
 * onBeforeUnmount 不会跑。keydown 若只在 onBeforeUnmount 里摘，切走后幽灵监听
 * 会继续用**这个陈旧实例的 meta** 响应 Ctrl+S，把旧主题/旧布局覆盖回库里。
 * 所以绑与解绑都要覆盖 activated / deactivated 两条路径。
 */
function bindKey() { window.addEventListener('keydown', onKey); }
function unbindKey() { window.removeEventListener('keydown', onKey); }

onActivated(() => {
  bindKey();
  lockHost();
});
onDeactivated(() => {
  unbindKey();
  unlockHost();
});

onBeforeUnmount(() => {
  unbindKey();
  unlockHost();
});
</script>

<style scoped>
.bi-designer {
  display: flex;
  flex-direction: column;
  /* 高度交给 lockHost() 铺好的 flex 列容器：吃掉 TabBar 之外的剩余空间。
     不能写 100vh（比 el-main 可视区高出一截 → .main 出竖向滚动条），
     也不能写 100%（宿主里还有 TabBar，会再叠一次 100% 照样溢出）。 */
  flex: 1 1 auto;
  min-height: 0;
  background: var(--color-bg);
  overflow: hidden;
}
.designer-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 8px 14px;
  background: var(--color-bg-elev);
  border-bottom: 1px solid var(--color-border);
  flex: 0 0 auto;
}
.topbar-left,
.topbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.banner {
  width: 1px;
  height: 16px;
  background: var(--color-border);
}
.designer-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text);
}
.designer-code {
  font-size: 12px;
  color: var(--color-text-muted);
  font-family: var(--font-mono);
}
.dirty-mark {
  color: var(--color-warning);
  font-size: 12px;
}
.theme-select {
  width: 130px;
}
.designer-body {
  flex: 1 1 auto;
  display: flex;
  min-height: 0;
}
.pane {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.pane-left {
  width: 232px;
  flex: 0 0 232px;
  background: var(--color-bg-elev);
  border-right: 1px solid var(--color-border);
}
.pane-right {
  /* 300px 时「关联查询 / 聚合方式 / 排序字段」那几行的 el-select 只剩 ~130px，
     中文占位符直接被截断。给到 320px，label 与控件都喘得过气。 */
  width: 320px;
  flex: 0 0 320px;
  background: var(--color-bg-elev);
  border-left: 1px solid var(--color-border);
}
.pane-center {
  /* flex-basis 用 0 而不是 auto：auto 会让 flex item 的基准尺寸 = 内容尺寸，
     而内容里就是画布本身 —— 画布一变宽中栏跟着变宽，ResizeObserver 量到的值
     又是上一次的尺寸，形成「测量值影响被测量物」的回环，fit 会算飘。
     basis: 0 让中栏宽度只由容器决定，与画布无关。 */
  flex: 1 1 0;
  min-width: 0;
  min-height: 0;
}
.pane-tabs {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.pane-tabs :deep(.el-tabs__content) {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
}
.meta-pane {
  padding: 12px 10px;
}
.grid-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.grid-row .x {
  color: var(--color-text-muted);
}
.field-hint {
  font-size: 11px;
  color: var(--color-text-muted);
  line-height: 1.4;
  margin-top: 2px;
}
.json-pane {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 12px;
  gap: 8px;
  background: var(--color-bg);
}
.json-input {
  flex: 1 1 auto;
}
.json-input :deep(textarea) {
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.5;
}
.json-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
.json-errors {
  color: var(--color-danger);
  font-size: 12px;
}
</style>