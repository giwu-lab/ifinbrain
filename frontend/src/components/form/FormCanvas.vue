<!--
  FormCanvas —— 三模式画布（mfish 风格）：
    build   — 24 列栅格，支持拖拽移动 / 右侧手柄改宽度 / 选中 / 复制 / 删除
    preview — 动态表单渲染（与运行时同一个 FormRenderer，所见即所得）
    json    — 字段 JSON 展示 + 复制按钮
  deviceMode 决定 canvasWidth（desktop/tablet/mobile），zoom 缩放。
-->
<template>
  <div class="form-canvas" :class="{ 'is-empty': !fields.length }">
    <template v-if="!fields.length">
      <div class="canvas-empty muted">{{ t('formDesigner.canvasEmpty') }}</div>
    </template>

    <!-- =================== BUILD ===================
         关键设计：这里渲染的就是运行态那个 FormRenderer，**不是另写一套**。
         栅格、字段顺序、label 排版、style、表达式求值全部同一条路径 ——
         所见即所得不是靠"两边数值对齐"维持的，而是结构上就不可能漂移。
         设计器专属的拖拽热区 / 操作栏 / 手柄做成**绝对定位浮层**挂在格子上方，
         不占文档流，因此不会把字段内容往下顶。 -->
    <div
      v-else-if="mode === 'build'"
      class="canvas-build"
      :style="stageStyle"
    >
      <DynamicFormRenderer
        :fields="fields"
        :grid="grid"
        :model-value="previewData"
        :readonly="false"
        :design-mode="true"
        @update:model-value="(v) => previewData = v"
      >
        <template #cell="{ field: f, item, index: idx }">
          <div
            class="cell-overlay"
            :class="{ 'is-selected': selectedId === f.id, 'is-dragging': dragId === f.id }"
            @mousedown.stop.prevent="startDrag(f, $event)"
            @click.stop="select(f.id)"
          >
            <!-- 上方信息条：贴在格子外侧，不遮挡字段本身 -->
            <div class="cell-meta">
              <span class="row-index">{{ idx + 1 }}</span>
              <span class="row-label">{{ item.rf.label || t('formDesigner.unnamed') }}</span>
              <span v-if="item.required" class="required-mark">*</span>
              <span class="row-type">{{ typeLabel(f.type) }}</span>
              <span v-if="f.key" class="row-key">{{ f.key }}</span>
              <span v-if="!item.visible" class="cell-hidden">{{ t('formDesigner.grid.hiddenByCond') }}</span>
              <span v-if="condBadge(f)" class="cell-cond">{{ condBadge(f) }}</span>
            </div>

            <!-- 下方操作条：同样浮在格子外侧 -->
            <div class="cell-actions" @mousedown.stop>
              <el-tooltip :content="t('formDesigner.grid.widen')" placement="top">
                <el-button link :icon="Sort" size="small" @click.stop="widen(f)" />
              </el-tooltip>
              <el-tooltip :content="t('formDesigner.grid.narrow')" placement="top">
                <el-button link :icon="Sort" size="small" @click.stop="narrow(f)" />
              </el-tooltip>
              <el-tooltip :content="t('common.up')" placement="top">
                <el-button link :icon="ArrowUp" size="small" :disabled="idx === 0" @click.stop="moveField(f.id, 'up')" />
              </el-tooltip>
              <el-tooltip :content="t('common.down')" placement="top">
                <el-button link :icon="ArrowDown" size="small" :disabled="idx === fields.length - 1" @click.stop="moveField(f.id, 'down')" />
              </el-tooltip>
              <el-tooltip :content="t('common.duplicate')" placement="top">
                <el-button link :icon="DocumentCopy" size="small" @click.stop="duplicate(f.id)" />
              </el-tooltip>
              <el-tooltip :content="t('common.delete')" placement="top">
                <el-button link :icon="Delete" size="small" class="danger-icon" @click.stop="remove(f.id)" />
              </el-tooltip>
            </div>

            <!-- 右侧宽度手柄：8px 热区，选中时才出现 -->
            <div
              v-if="selectedId === f.id"
              class="cell-resize"
              @mousedown.stop.prevent="startResize(f, $event)"
            />
          </div>
        </template>
      </DynamicFormRenderer>
    </div>

    <!-- =================== PREVIEW =================== -->
    <div
      v-else-if="mode === 'preview'"
      class="canvas-preview"
      :style="stageStyle"
    >
      <DynamicFormRenderer
        :fields="fields"
        :grid="grid"
        :model-value="previewData"
        :readonly="false"
        @update:model-value="(v) => previewData = v"
      />
    </div>

    <!-- =================== JSON =================== -->
    <div v-else class="canvas-json">
      <div class="json-card">
        <div class="json-header">
          <span class="json-title">
            <el-icon><Document /></el-icon>
            <span>{{ t('formDesigner.jsonTitle') }}</span>
          </span>
          <el-button :icon="CopyDocument" size="small" @click="copyJson">{{ t('formDesigner.jsonCopy') }}</el-button>
        </div>
        <pre class="json-content">{{ jsonText }}</pre>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { ArrowUp, ArrowDown, DocumentCopy, Delete, Document, CopyDocument, Sort } from '@element-plus/icons-vue';
import DynamicFormRenderer from './FormRenderer.vue';
import { DEFAULT_GRID } from '@/utils/form/layout';
import type { FormFieldSchema, FieldType, FormGridConfig } from '@/api/formDesign';
import type { DesignerMode } from '@/stores/formDesign';

const props = defineProps<{
  fields: FormFieldSchema[];
  selectedId: string | null;
  mode: DesignerMode;
  canvasWidth: number;
  zoom: number;
  grid?: FormGridConfig;
}>();
const emit = defineEmits<{
  select: [id: string];
  remove: [id: string];
  move: [id: string, direction: 'up' | 'down'];
  duplicate: [id: string];
  layout: [id: string, patch: Partial<{ x: number; y: number; w: number; h: number }>, immediate?: boolean];
}>();
const { t } = useI18n();

const cfg = computed<FormGridConfig>(() => props.grid ?? DEFAULT_GRID);

/**
 * build / preview 两种模式的舞台样式，**必须完全一致** —— 否则两边排版又会漂。
 *
 * 宽度用 `width:100%` + `max-width:设备宽` 而不是写死 `width:设备宽`：
 * 写死时中心栏比设备宽窄时只能靠 flex-shrink 挤压（等于放弃设备语义），
 * 比设备宽宽时卡片又撑不满、两侧露出底色。现在是「有多少用多少，最多到设备宽」。
 */
const stageStyle = computed(() => ({
  maxWidth: `${props.canvasWidth}px`,
  transform: `scale(${props.zoom})`,
  transformOrigin: 'top center',
}));

const previewData = ref<Record<string, any>>({});

const jsonText = computed(() =>
  JSON.stringify({ version: 1, fields: props.fields }, null, 2)
);

function select(id: string) { emit('select', id); }
function remove(id: string) { emit('remove', id); }
function moveField(id: string, direction: 'up' | 'down') { emit('move', id, direction); }
function duplicate(id: string) { emit('duplicate', id); }

function typeLabel(type: FieldType): string {
  return t(`formDesigner.type.${type}`);
}

/** 条件提示徽标：让设计师不展开属性面板也知道这字段被什么控制着 */
function condBadge(f: FormFieldSchema): string {
  const parts: string[] = [];
  if (f.visibleWhen) parts.push(t('formDesigner.grid.badgeVisible'));
  if (f.disabledWhen) parts.push(t('formDesigner.grid.badgeDisabled'));
  if (f.requiredWhen) parts.push(t('formDesigner.grid.badgeRequired'));
  return parts.join(' · ');
}

/* ===================== 拖拽 ===================== */

/**
 * 拖拽 / 缩放走「鼠标位移 → 像素换算 → 栅格坐标」。
 *
 * 两个关键取舍：
 * 1. **拖拽过程中只改内存（immediate=false），松手才 commit 一次**。
 *    否则每动一像素压一帧撤销栈，拖十次历史就满了，用户没法撤销。
 * 2. 宽度用像素拖动体验更顺（连续跟手），落手时再吸附回整列。
 *    实时吸附到列会让手柄「咔咔跳」，很难对准。
 */
const dragId = ref<string | null>(null);
let cleanup: (() => void) | null = null;

/** 拖拽期间暂停 transition，否则每帧都做一次补间动画 */
function bindDragging(on: boolean) {
  document.body.classList.toggle('is-form-dragging', on);
}

/** 画布单格像素宽（扣除 gap），用于 px → 栅格换算 */
function stepX(): number {
  const w = props.canvasWidth || 1200;
  return (w - cfg.value.gap * (cfg.value.cols - 1)) / cfg.value.cols + cfg.value.gap;
}

function startDrag(f: FormFieldSchema, ev: MouseEvent) {
  const startX = ev.clientX;
  const startY = ev.clientY;
  const origin = { ...(f.layout ?? { x: 0, y: 0, w: cfg.value.cols, h: 1 }) };
  const sx = stepX();
  const sy = cfg.value.rowHeight + cfg.value.gap;

  dragId.value = f.id;
  bindDragging(true);

  const onMove = (e: MouseEvent) => {
    const dx = Math.round((e.clientX - startX) / sx);
    const dy = Math.round((e.clientY - startY) / sy);
    if (dx === 0 && dy === 0) return;
    emit('layout', f.id, { x: origin.x + dx, y: origin.y + dy }, false);
  };

  const onUp = () => {
    cleanup?.();
    cleanup = null;
    dragId.value = null;
    bindDragging(false);
    // 松手提交一次：位置已经是终值，补一发 commit 让撤销栈记下这一步
    emit('layout', f.id, {}, true);
  };

  window.addEventListener('mousemove', onMove);
  window.addEventListener('mouseup', onUp, { once: true });
  cleanup = () => {
    window.removeEventListener('mousemove', onMove);
    window.removeEventListener('mouseup', onUp);
  };
}

function startResize(f: FormFieldSchema, ev: MouseEvent) {
  const startX = ev.clientX;
  const originW = f.layout?.w ?? cfg.value.cols;
  const sx = stepX();
  dragId.value = f.id;
  bindDragging(true);

  const onMove = (e: MouseEvent) => {
    const dw = Math.round((e.clientX - startX) / sx);
    if (dw === 0) return;
    emit('layout', f.id, { w: originW + dw }, false);
  };

  const onUp = () => {
    cleanup?.();
    cleanup = null;
    dragId.value = null;
    bindDragging(false);
    emit('layout', f.id, {}, true);
  };

  window.addEventListener('mousemove', onMove);
  window.addEventListener('mouseup', onUp, { once: true });
  cleanup = () => {
    window.removeEventListener('mousemove', onMove);
    window.removeEventListener('mouseup', onUp);
  };
}

onBeforeUnmount(() => {
  cleanup?.();
  bindDragging(false);
});

/** 快捷加减宽度：按整列步进，给不想拖手柄的人用 */
function widen(f: FormFieldSchema) {
  emit('layout', f.id, { w: (f.layout?.w ?? cfg.value.cols) + Math.floor(cfg.value.cols / 2) });
}
function narrow(f: FormFieldSchema) {
  emit('layout', f.id, { w: Math.max(1, (f.layout?.w ?? cfg.value.cols) - Math.floor(cfg.value.cols / 2)) });
}

async function copyJson() {
  try {
    await navigator.clipboard.writeText(jsonText.value);
    ElMessage.success(t('design.copied'));
  } catch {
    ElMessage.warning('Clipboard not available');
  }
}
</script>

<style scoped>
.form-canvas {
  width: 100%;
  /*
   * height 必须给确定值：卡片靠 min-height:100% 铺满，百分比得有个确定参照。
   * min-height 是兜底 —— 万一父级高度链断掉（height:100% 退化成 auto），
   * 至少画布区不被内容高度截断。
   */
  height: 100%;
  min-height: 100%;
  /* 内容比可视区长时出竖向滚动条；缩放放大时也能横向平移 */
  overflow: auto;
  /* 滚动条常驻预留，避免"内容变长→出现滚动条→卡片宽度变窄→居中位置跳一下" */
  scrollbar-gutter: stable;
  background: var(--color-border-soft);
  display: flex;
  /*
   * flex-start + 卡片 min-height:100%（而不是 stretch）：
   *   内容短 → 卡片被 min-height 撑满，白色「页面」铺满整块画布区；
   *   内容长 → 卡片按内容长高，容器 overflow:auto 就出现竖向滚动条。
   * 用 stretch 会把卡片高度永久钉死成容器高，反而永远等不到滚动条。
   */
  align-items: flex-start;
  justify-content: center;
  padding: 24px;
}
.form-canvas.is-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  font-size: 13px;
  padding: 80px 24px;
}

.canvas-build,
.canvas-preview {
  /* 宽度填满可用区域，以设备宽封顶（见 stageStyle 注释） */
  width: 100%;
  /* 撑满画布区高度 —— 内容更长时这个值被自然高度顶过，于是容器出竖向滚动条 */
  min-height: 100%;
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 24px;
}
.canvas-build-inner { min-height: 200px; }

/*
 * 画布里禁止真的操作控件（否则打字会写进一份没保存的 previewData），
 * 交互一律走上面的浮层。字段本身的渲染样式一点不改 —— 这是所见即所得的前提。
 */
.canvas-build :deep(.dynamic-form-renderer) { pointer-events: none; }

/*
 * 浮层：覆盖整格，负责选中 / 拖拽热区。
 * 用 outline 而不是 border 画选中框 —— border 会改变盒模型尺寸，
 * 那样「选中」这一下就会让字段挪位置，正是要避免的。
 */
.cell-overlay {
  position: absolute;
  inset: 0;
  pointer-events: auto;
  cursor: grab;
  border-radius: var(--radius-xs);
  outline: 1px dashed transparent;
  outline-offset: 2px;
  transition: outline-color 0.1s, background 0.1s;
}
.cell-overlay:hover { outline-color: var(--color-primary); }
.cell-overlay.is-selected {
  outline: 2px solid var(--color-primary);
  outline-style: solid;
  background: color-mix(in srgb, var(--color-primary) 6%, transparent);
}
.cell-overlay:active { cursor: grabbing; }
.cell-overlay.is-dragging { transition: none; opacity: 0.85; z-index: 5; }

/* 上方信息条：贴在格子**外侧**，不覆盖字段内容 */
.cell-meta {
  position: absolute;
  left: 0;
  bottom: 100%;
  margin-bottom: 4px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 8px;
  border-radius: var(--radius-xs);
  background: var(--color-bg);
  border: 1px solid var(--color-border-soft);
  box-shadow: 0 1px 3px rgb(0 0 0 / 8%);
  font-size: 12px;
  white-space: nowrap;
  max-width: 100%;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.1s;
}
.cell-overlay:hover .cell-meta,
.cell-overlay.is-selected .cell-meta { opacity: 1; pointer-events: auto; }

.row-index {
  font-size: 11px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--color-text-muted);
  flex: none;
}
.row-label {
  font-weight: 500;
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}
.required-mark { color: var(--color-danger); margin-left: 2px; flex: none; }
.row-type {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 2px;
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  color: var(--color-text-secondary);
  flex: none;
}
.row-key {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--color-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}
.cell-cond { font-size: 11px; flex: none; color: var(--color-warning); }
.cell-hidden {
  font-size: 11px;
  flex: none;
  color: var(--color-text-secondary);
  border: 1px dashed var(--color-border);
  border-radius: 2px;
  padding: 0 4px;
}

/* 下方操作条：同样浮在格子外侧，默认收起，hover / 选中才出现 */
.cell-actions {
  position: absolute;
  right: 0;
  top: 100%;
  margin-top: 4px;
  display: flex;
  gap: 2px;
  align-items: center;
  padding: 0 4px;
  border-radius: var(--radius-xs);
  background: var(--color-bg);
  border: 1px solid var(--color-border-soft);
  box-shadow: 0 1px 3px rgb(0 0 0 / 8%);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.1s;
}
.cell-overlay:hover .cell-actions,
.cell-overlay.is-selected .cell-actions { opacity: 1; pointer-events: auto; }
.danger-icon :deep(.el-icon) { color: var(--color-danger); }

/* 右侧宽度手柄：8px 热区，选中时才出现 */
.cell-resize {
  position: absolute;
  top: 0;
  right: -4px;
  width: 8px;
  height: 100%;
  cursor: ew-resize;
  z-index: 6;
}
.cell-resize::after {
  content: '';
  position: absolute;
  top: 50%;
  right: 3px;
  transform: translateY(-50%);
  width: 2px;
  height: 24px;
  border-radius: 1px;
  background: var(--color-primary);
  opacity: 0.6;
}
.cell-resize:hover::after { opacity: 1; height: 40px; }

/* JSON mode */
.canvas-json { width: 100%; max-width: 1200px; }
.json-card {
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.json-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--color-border-soft);
  background: var(--color-bg-elev);
}
.json-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  font-size: 13px;
}
.json-content {
  margin: 0;
  padding: 16px;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.6;
  color: var(--color-text);
  background: var(--color-bg);
  max-height: 600px;
  overflow: auto;
  white-space: pre;
}
</style>