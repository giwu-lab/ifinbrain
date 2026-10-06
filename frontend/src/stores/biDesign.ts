import { computed, ref, watch } from 'vue';
import { defineStore } from 'pinia';
import type {
  BiDashboardDto, BiWidget, DashboardLayout, DashboardTheme, WidgetType,
} from '@/api/bi';
import {
  clampWidget, cloneWidget, defaultLayout, deepClone, findWidget, makeWidget,
  migrateLayout, normalizeZ, validateLayout,
} from '@/utils/bi/layout';

export type BiDesignerMode = 'build' | 'preview' | 'json';

/** 撤销栈容量：M2 验收要求「撤销 10 步不崩」，50 步足够且不会撑爆内存 */
const HISTORY_CAP = 50;

/**
 * BI 大屏设计器 store。
 * 结构与 formDesign.ts 保持一致：meta + widgets + selectedId + history 栈 + dirty。
 * 差异点：拖拽期间不 commit（避免鼠标每动一像素压一帧栈），由拖拽结束统一 commit。
 */
export const useBiDesignStore = defineStore('biDesign', () => {
  const meta = ref<BiDashboardDto | null>(null);
  const widgets = ref<BiWidget[]>([]);
  const layout = ref<DashboardLayout>(defaultLayout());
  const selectedId = ref<string | null>(null);
  const dirty = ref(false);
  const loading = ref(false);
  const zoom = ref(1);
  const mode = ref<BiDesignerMode>('build');
  const history = ref<string[]>([]);
  const historyIndex = ref(-1);

  const selectedWidget = computed(() => findWidget(widgets.value, selectedId.value));

  /** 按 z 升序渲染，保证叠放顺序与数据一致 */
  const orderedWidgets = computed(() =>
    [...widgets.value].sort((a, b) => a.z - b.z));

  const canvas = computed(() => layout.value.canvas);
  const theme = computed(() => layout.value.theme);

  // widgets 与 layout.widgets 保持同步由 syncWidgetsToLayout() 显式负责，
  // 不用 deep watch 双向同步 —— 避免「赋值触发自己」的死循环。
  function syncWidgetsToLayout() {
    layout.value.widgets = widgets.value;
  }

  // ===================== Reset / 初始化 =====================

  function reset(item?: BiDashboardDto) {
    meta.value = item ?? null;
    const base = item?.layout ? migrateLayout(item.layout) : defaultLayout();
    if (item?.theme) base.theme = { ...base.theme, ...item.theme };
    layout.value = base;
    widgets.value = base.widgets;
    selectedId.value = null;
    dirty.value = false;
    history.value = [];
    historyIndex.value = -1;
    saveHistory();
  }

  function setLoading(v: boolean) { loading.value = v; }
  function markDirty() { dirty.value = true; }
  function clearDirty() { dirty.value = false; }
  function setMode(m: BiDesignerMode) { mode.value = m; }
  function setZoom(z: number) { zoom.value = Math.max(0.5, Math.min(1.5, z)); }
  function select(id: string | null) { selectedId.value = id; }

  // ===================== 撤销 / 重做 =====================

  function snapshot(): string {
    return JSON.stringify(widgets.value);
  }

  function commit() {
    if (historyIndex.value + 1 < history.value.length) {
      history.value = history.value.slice(0, historyIndex.value + 1);
    }
    history.value.push(snapshot());
    if (history.value.length > HISTORY_CAP) {
      history.value = history.value.slice(history.value.length - HISTORY_CAP);
    }
    historyIndex.value = history.value.length - 1;
    syncWidgetsToLayout();
    dirty.value = true;
  }

  function saveHistory() {
    if (history.value.length === 0) {
      history.value = [snapshot()];
      historyIndex.value = 0;
    }
  }

  function undo() {
    if (historyIndex.value <= 0) return;
    historyIndex.value--;
    widgets.value = JSON.parse(history.value[historyIndex.value]);
    syncWidgetsToLayout();
    dirty.value = true;
  }

  function redo() {
    if (historyIndex.value >= history.value.length - 1) return;
    historyIndex.value++;
    widgets.value = JSON.parse(history.value[historyIndex.value]);
    syncWidgetsToLayout();
    dirty.value = true;
  }

  const canUndo = computed(() => historyIndex.value > 0);
  const canRedo = computed(() => historyIndex.value < history.value.length - 1);

  // ===================== 组件操作 =====================

  /** 找一个不与已有组件重叠的落点 */
  function findFreeSlot(w: number, h: number): { x: number; y: number } {
    const cols = canvas.value.gridCols;
    const rows = canvas.value.gridRows;
    const occupied = widgets.value.map((x) => ({
      x: x.x, y: x.y, w: x.w, h: x.h,
    }));
    const hits = (x: number, y: number) =>
      occupied.some((o) => x < o.x + o.w && x + w > o.x && y < o.y + o.h && y + h > o.y);
    for (let y = 0; y <= rows - h; y += Math.max(1, h)) {
      for (let x = 0; x <= cols - w; x += Math.max(1, w)) {
        if (!hits(x, y)) return { x, y };
      }
    }
    return { x: 0, y: 0 };
  }

  function addWidget(type: WidgetType): BiWidget {
    const tpl = makeWidget(type, 0, 0);
    const slot = findFreeSlot(tpl.w, tpl.h);
    const z = widgets.value.length ? Math.max(...widgets.value.map((v) => v.z)) + 1 : 0;
    const created = makeWidget(type, slot.x, slot.y, { z });
    widgets.value = normalizeZ([...widgets.value, created]);
    selectedId.value = created.id;
    commit();
    return created;
  }

  function removeWidget(id: string) {
    widgets.value = widgets.value.filter((w) => w.id !== id);
    if (selectedId.value === id) selectedId.value = null;
    commit();
  }

  function duplicateWidget(id: string) {
    const src = widgets.value.find((w) => w.id === id);
    if (!src) return;
    const copy = clampWidget(cloneWidget(src), canvas.value);
    widgets.value = normalizeZ([...widgets.value, copy]);
    selectedId.value = copy.id;
    commit();
  }

  /**
   * 更新组件。
   * @param commitNow 拖拽/缩放过程中传 false（只改内存），结束再手动 commit()
   */
  function updateWidget(id: string, patch: Partial<BiWidget>, commitNow = true) {
    const i = widgets.value.findIndex((w) => w.id === id);
    if (i < 0) return;
    widgets.value[i] = { ...widgets.value[i], ...patch };
    if (commitNow) commit();
    else syncWidgetsToLayout();
  }

  /** 移动 / 缩放后统一夹回画布并提交一次 */
  function commitGeometry(id: string) {
    const i = widgets.value.findIndex((w) => w.id === id);
    if (i < 0) return;
    widgets.value[i] = clampWidget(widgets.value[i], canvas.value);
    commit();
  }

  // ===================== 图层顺序 =====================

  function bringToFront(id: string) {
    const w = widgets.value.find((x) => x.id === id);
    if (!w) return;
    updateWidget(id, { z: Math.max(...widgets.value.map((v) => v.z)) + 1 });
  }

  function sendToBack(id: string) {
    const w = widgets.value.find((x) => x.id === id);
    if (!w) return;
    updateWidget(id, { z: Math.min(...widgets.value.map((v) => v.z)) - 1 });
    widgets.value = normalizeZ(widgets.value);
    commit();
  }

  function moveLayer(id: string, dir: 1 | -1) {
    const sorted = orderedWidgets.value;
    const i = sorted.findIndex((w) => w.id === id);
    const j = i + dir;
    if (i < 0 || j < 0 || j >= sorted.length) return;
    const zi = sorted[i].z;
    const zj = sorted[j].z;
    const a = widgets.value.find((w) => w.id === sorted[i].id)!;
    const b = widgets.value.find((w) => w.id === sorted[j].id)!;
    a.z = zj;
    b.z = zi;
    widgets.value = normalizeZ(widgets.value);
    commit();
  }

  // ===================== 画布 / 主题 =====================

  function updateCanvas(patch: Partial<DashboardLayout['canvas']>) {
    layout.value.canvas = { ...layout.value.canvas, ...patch };
    // 栅格变小后夹回所有组件，避免越界
    widgets.value = widgets.value.map((w) => clampWidget(w, layout.value.canvas));
    syncWidgetsToLayout();
    commit();
  }

  function updateTheme(patch: Partial<DashboardTheme>) {
    layout.value.theme = { ...layout.value.theme, ...patch };
    syncWidgetsToLayout();
    commit();
  }

  // ===================== 校验 / 导出 =====================

  function validate() {
    syncWidgetsToLayout();
    return validateLayout(layout.value);
  }

  /** 存库快照 */
  function toLayout(): DashboardLayout {
    return {
      version: layout.value.version,
      canvas: { ...layout.value.canvas },
      theme: { ...layout.value.theme },
      widgets: deepClone(widgets.value),
    };
  }

  /** JSON 模式下编辑后回写 */
  function applyLayoutJson(raw: DashboardLayout): { ok: boolean; errors: string[] } {
    const migrated = migrateLayout(raw);
    layout.value = migrated;
    widgets.value = migrated.widgets;
    selectedId.value = null;
    const v = validateLayout(migrated);
    if (v.ok) commit();
    return v;
  }

  return {
    // 状态
    meta, widgets, layout, selectedId, selectedWidget, orderedWidgets,
    dirty, loading, zoom, mode, canvas, theme,
    canUndo, canRedo,
    // 生命周期
    reset, setLoading, markDirty, clearDirty, setMode, setZoom, select,
    // 历史
    commit, undo, redo, saveHistory,
    // 组件
    addWidget, removeWidget, duplicateWidget, updateWidget, commitGeometry,
    // 图层
    bringToFront, sendToBack, moveLayer,
    // 画布 / 主题
    updateCanvas, updateTheme,
    // 校验
    validate, toLayout, applyLayoutJson,
  };
});