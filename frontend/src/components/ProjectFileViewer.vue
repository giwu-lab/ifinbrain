<script setup lang="ts">
/**
 * ProjectFileViewer —— 项目档案库文件预览包装
 *
 * 设计要点：
 *  1. 对外契约与旧 FilePreviewer.vue 一致：
 *     - props: storageKey + filename
 *     - emit('download', { storageKey, name })
 *     - 父组件不感知底层从 if-else 切换到 @file-viewer
 *
 *  2. 内部走 @file-viewer/preset-office（替代旧 PdfViewer/mammoth/SheetJS 散件）
 *     - fetch /api/files/raw（带 JWT）→ ArrayBuffer → 喂 file-viewer
 *     - file-viewer 接 File/ArrayBuffer/Blob；后端鉴权由 FileController 守住
 *
 *  3. 关键风险点已解决（旧实现踩过）：
 *     - PDF.js worker 不再走 /@fs/...：file-viewer 的 copyAssets 把 worker 落到 public/vendor/
 *     - 不再需要 utils/pdfWorker.ts 的 Vite 注入剥除 hack
 *     - dompurify 由 file-viewer core 内置（sanitize docx/rtf HTML）
 *
 *  4. 父组件仅做容器 + 加载状态；具体格式识别 / 渲染 / 搜索 / 缩放 / 打印 / 水印
 *     全部交给 file-viewer 处理。
 */

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import {
  Download, FullScreen, Refresh, ZoomIn, ZoomOut
} from '@element-plus/icons-vue';
import { createViewer, setDefaultFileViewerAssetBaseUrl } from '@file-viewer/core/browser';
import { officeRenderers } from '@file-viewer/preset-office';
import { imageRenderer } from '@file-viewer/renderer-image';
import { textRenderer } from '@file-viewer/renderer-text';
import { enableFileViewerTextTools } from '@file-viewer/capability-text-tools';
import type { FileViewerLifecycleContext, FileViewerOperationContext, FileViewerZoomState } from '@file-viewer/core';
import { fileRawUrl } from '@/api/document';

// opt-in 能力：注册 patch 比较（diff2html）+ Git bundle 解析（pako）
// 必须先于 createViewer 调用，否则 textRenderer 收不到这些 hook
enableFileViewerTextTools();

const props = defineProps<{
  storageKey: string;
  filename: string;
}>();

const emit = defineEmits<{
  (e: 'download', row: { storageKey: string; name: string }): void;
}>();

const { t } = useI18n();

const loading = ref(true);
const errorMsg = ref('');
const viewerRef = ref<HTMLElement | null>(null);
let viewer: ReturnType<typeof createViewer> | null = null;

// 缩放状态（仅在 renderer 支持 zoom 时由 onEvent('zoom-change') 填充）
// 显示为右下角浮层 + 键盘快捷键 +/=/-/0/F
const zoomState = ref<FileViewerZoomState | null>(null);
const supportsZoom = computed(() => zoomState.value !== null);

function handleViewerEvent(event: { type: string; payload: unknown }) {
  if (event.type === 'zoom-change') {
    zoomState.value = event.payload as FileViewerZoomState;
  } else if (event.type === 'load-start' || event.type === 'load-complete') {
    // 切换文件时清空旧 zoom 状态，等下一个 zoom-change 填充
    if (event.type === 'load-start') zoomState.value = null;
  }
}

async function zoomIn() {
  if (!viewer) return;
  await viewer.zoomIn();
}
async function zoomOut() {
  if (!viewer) return;
  await viewer.zoomOut();
}
async function zoomReset() {
  if (!viewer) return;
  await viewer.resetZoom();
}
async function zoomFit() {
  if (!viewer) return;
  await viewer.fitToView({ mode: 'scale-down', resize: 'initial' });
}

setDefaultFileViewerAssetBaseUrl('/vendor/');

async function load() {
  loading.value = true;
  errorMsg.value = '';
  await destroyViewer();
  if (!viewerRef.value) return;

  try {
    const token = localStorage.getItem('accessToken') || '';
    const resp = await fetch(fileRawUrl(props.storageKey, false), {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!resp.ok) {
      throw new Error(`HTTP ${resp.status}`);
    }
    const ab = await resp.arrayBuffer();
    if (!ab || ab.byteLength === 0) {
      throw new Error(t('documents.previewError'));
    }

    viewer = createViewer(viewerRef.value, {
      onEvent: handleViewerEvent,
      options: {
        builtinRenderers: 'none',
        rendererMode: 'replace',
        // 文本格式化（v1.5.3）
        // 启用 file-viewer 内置的 Prettier-based pretty-print：
        //   - JSON/JSON5/JSONC/JS/TS/Vue/YAML/Markdown/CSS/HTML/XML 等
        //   - 加载时自动格式化 + 顶部 toolbar 加「Show original」toggle
        //   - > 512KB 自动回退原始内容，失败也不报错
        // 单独开 prettyPrint：保留各格式的源文本高亮（language-hljs）
        // 不开 lineNumbers / wrapLongLines：档案库 JSON 通常不超百行
        text: { prettyPrint: true },
        // preset-office 8 个 renderer + image + text；capability-text-tools 已在
        // 模块加载时通过 enableFileViewerTextTools() 注册到 textRenderer 内部。
        // 强转 any：officeRenderers / imageRenderer / textRenderer 的 Handler 泛型
        // 不同，TS 静态无法对账；file-viewer runtime 接受任意 FileRenderHandler。
        renderers: [...officeRenderers.renderers, imageRenderer, textRenderer] as any,
        styleIsolation: 'scoped',
        toolbar: { position: 'bottom-right' },
        // 强制亮色主题：不随 OS / 浏览器 prefers-color-scheme 切换
        theme: 'light',
        // 不设顶层 fit 选项 —— 让每个 renderer 用自己的默认 fit 逻辑：
        //   - 图片：fit-scale = min(1, w/natW, h/natH)，永不超出抽屉
        //   - 代码/Excel：renderer 各自的 fit
        //   - PDF：默认 fit-to-width（~112% 撑满抽屉），但 v1.5.3 在 viewer.load()
        //     完成后用 fitToView({mode:'actual', scale:1}) 强制覆盖为 100%
        //       —— 见下方 PDF 100% 逻辑块
        //
        // 历史：曾设 fit.mode:'actual'，意图 PDF 按 scale=1 渲染；但 image
        // renderer 也吃这个 option，导致 2480px 的图直接渲到 2480px+，
        // 远超抽屉宽度溢出。每个 renderer 的 fit 算法不同 —— 不应该共用。
        // 用户如要图片放大看细节：用下面新增的右下角缩放工具栏 / 快捷键。
        // beforeOperation 是顶层选项；download 由 emit 转给父组件统一处理（避免 file-viewer 内部
        // 与我们的 download() 双触发）。per-archive view perm 已在 FileController.raw() 守住。
        beforeOperation(ctx: FileViewerOperationContext) {
          if (ctx.operation === 'download') {
            emit('download', { storageKey: props.storageKey, name: props.filename });
            return false;
          }
          return true;
        },
        hooks: {
          onLoadStart(_ctx: FileViewerLifecycleContext) {
            loading.value = true;
          },
          onLoadComplete(_ctx: FileViewerLifecycleContext) {
            loading.value = false;
          },
        },
      },
    });
    await viewer.load({ buffer: ab, filename: props.filename });
    loading.value = false;
    // 取一次初始 zoom 状态填充缩放工具栏（renderer 不会自动 emit zoom-change，
    // 必须主动 getZoomState() 才能拿到初始 scale / label）
    const initZoom = viewer.getZoomState?.();
    if (initZoom) {
      zoomState.value = initZoom;
    }
    // PDF 默认 100% 缩放（v1.5.3）。
    // 走 fitToView('actual') 而非 applyViewState({scale:1})：
    //   fitToView 走 zoom provider 的 setZoom 通道，会显式 markFitInteraction('api')
    //   关掉 PDF renderer 的 autoFitWidth，阻止后续 ResizeObserver / pagesinit 的
    //   fitToWidth() 覆盖回 fit-width（实测：applyViewState 设的 scale 会被 pagesinit
    //   的 fitToWidth 抢回去，又变 112%）。
    // 真实场景：PDF.js pagesinit 默认 fit-to-width（A4 撑满抽屉）算出 ~112%；
    //   这里用 'actual' 模式强制 1:1 渲染，canvas 像素 = A4 物理像素（96 DPI）。
    // 注意：FileViewerFitOptions 只有 mode / resize / padding / minScale / maxScale，
    //   **没有 scale 字段**。原先这里传 { mode:'actual', scale:1 }，其中 scale 会被
    //   静默忽略（JS 不报错、TS 才抱怨），1:1 完全是靠 mode:'actual' 达成的 ——
    //   所以现在只传 mode，别再写 scale 以免误导下一个人以为它在生效。
    // 仅 PDF 文件触发：imageRenderer / textRenderer 的 fit 行为与 PDF 不同，不走这条。
    if (/\.pdf$/i.test(props.filename)) {
      viewer.fitToView?.('actual');
      const afterZoom = viewer.getZoomState?.();
      if (afterZoom) zoomState.value = afterZoom;
    }
  } catch (e: any) {
    errorMsg.value = e?.message || t('documents.previewError');
    loading.value = false;
    ElMessage.error(errorMsg.value);
  }
}

async function destroyViewer() {
  if (viewer) {
    try { await viewer.destroy(); } catch { /* ignore */ }
    viewer = null;
  }
}

// dev-only: body 横向越界元素探测（v1.5.1 v6）
// 抽屉打开后 ~1.5s 采样 body.scrollWidth vs clientWidth，
// 列出所有越过右视口边缘的元素到 DevTools Console。
// 用户打开任意 PDF 预览 → 看 Console 输出 → 把日志贴给我定位根因。
onMounted(() => {
  if (!import.meta.env.DEV) return;
  setTimeout(() => {
    const sw = document.body.scrollWidth;
    const cw = document.body.clientWidth;
    if (sw > cw + 1) {
      console.warn(
        '[preview-debug] body horizontal overflow detected:',
        `scrollWidth=${sw} clientWidth=${cw} viewport=${window.innerWidth}`
      );
      document.querySelectorAll('*').forEach((el) => {
        const r = el.getBoundingClientRect();
        if (r.right > window.innerWidth + 1) {
          console.warn(
            `  → <${el.tagName.toLowerCase()}.${(el.className || '').toString().slice(0, 80)}> right=${r.right.toFixed(0)}px`
          );
        }
      });
    }
  }, 1500);
});

function onDownload() {
  emit('download', { storageKey: props.storageKey, name: props.filename });
}

/*
 * 键盘快捷键（仅在缩放工具栏可见时激活）：
 *   +  / =     → zoomIn
 *   -  / _     → zoomOut
 *   0          → resetZoom (回到 1.0×)
 *   f  / F     → fitToView('scale-down')（适合窗口）
 *
 * 注意：必须忽略 input/textarea/contenteditable 上的按键，否则用户在抽屉里
 * 搜索/输入时会被劫持。
 */
function onKeyDown(e: KeyboardEvent) {
  if (!supportsZoom.value) return;
  const t = e.target as HTMLElement | null;
  if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return;
  if (e.ctrlKey || e.metaKey || e.altKey) return;  // 不抢浏览器原生快捷键
  switch (e.key) {
    case '+': case '=':
      e.preventDefault(); zoomIn(); break;
    case '-': case '_':
      e.preventDefault(); zoomOut(); break;
    case '0':
      e.preventDefault(); zoomReset(); break;
    case 'f': case 'F':
      e.preventDefault(); zoomFit(); break;
  }
}

onMounted(load);
watch(() => props.storageKey, load);
onMounted(() => window.addEventListener('keydown', onKeyDown));
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeyDown);
  destroyViewer();
});
</script>

<template>
  <div class="project-file-viewer">
    <el-skeleton v-if="loading" :rows="4" animated />
    <div v-else-if="errorMsg" class="preview-error">
      {{ errorMsg }}
      <el-button size="small" link :icon="Download" @click="onDownload">
        {{ t('common.download') }}
      </el-button>
    </div>
    <div ref="viewerRef" class="viewer-host"></div>
    <!--
      缩放工具栏：仅在 renderer 上报 zoom-change 事件时显示（图片 / PDF /
      其它支持 zoom 的格式）。图片场景下，@file-viewer 内置 toolbar 只有旋转，
      没有 zoom 按钮 —— 这层浮层填补这个空缺。
      键盘快捷键：+ / - 缩放，0 重置，F 适合窗口。
    -->
    <div v-if="supportsZoom && !loading && !errorMsg" class="preview-zoom-bar">
      <el-button-group size="small">
        <el-tooltip :content="t('documents.previewZoomOut')" placement="top">
          <el-button :icon="ZoomOut" :disabled="!zoomState?.canZoomOut" @click="zoomOut" />
        </el-tooltip>
        <el-tooltip :content="t('documents.previewZoomIn')" placement="top">
          <el-button :icon="ZoomIn" :disabled="!zoomState?.canZoomIn" @click="zoomIn" />
        </el-tooltip>
        <el-tooltip :content="t('documents.previewZoomReset')" placement="top">
          <el-button :icon="Refresh" :disabled="!zoomState?.canReset" @click="zoomReset" />
        </el-tooltip>
        <el-tooltip :content="t('documents.previewZoomFit')" placement="top">
          <el-button :icon="FullScreen" @click="zoomFit" />
        </el-tooltip>
      </el-button-group>
      <span class="preview-zoom-label">{{ zoomState?.label ?? '100%' }}</span>
    </div>
  </div>
</template>

<style scoped>
/*
 * 1) drawer 横向滚动条抑制（v1.5.1 累计 v4）
 *
 * fit:'width' 是 PDF 主修（line 95），但 fit 只在 initial 跑一次，
 * 浏览器缩放 / 抽屉尺寸变化 / 加载时序 race 都可能让某一层测量
 * 不准 → 横滚条漏出。这里在 PDF 内部 4 层 ancestor 强制 overflow-x:hidden。
 *
 * 注：el-drawer 的 .el-overlay / .el-drawer / .el-drawer__body 不在本 <style scoped>
 * 内处理 —— 见下方非 scoped <style> 块。原因：el-drawer 用 <Teleport to="body">
 * 把 overlay/drawer DOM 移到 <body> 下，是 ProjectFileViewer 的 **祖先** 而非后代；
 * :deep() 只能匹配后代，匹配不到祖先。所以这 3 个祖先层规则必须用全局非 scoped
 * CSS。
 *
 * 教训：v3 之前的版本里 :deep(.el-overlay) { overflow-x:hidden !important }
 * 编译为 [data-v-xxx] .el-overlay —— 永远不匹配（el-overlay 在 body 顶层），
 * 所以那个横滚条没被消除，v3 修了一半（仍漏 overlay），v4 才彻底解决。
 */
:deep(.pdf-shell),
:deep(.pdf-wrapper) {
  overflow-x: hidden !important;
}
/*
 * v1.5.2：用户偏好 PDF 1:1 原始尺寸渲染（不缩放不放大），PDF 比抽屉宽时
 * 在抽屉内部横向滚动。原本这里三件套都写 overflow-x:hidden，会裁掉
 * 溢出页内容；现改为 pdf-viewport/pdfViewer 横向 auto，抽屉内出现横滚条
 * （不溢出到 .main / body，与 §10.2 全局 7 层防御互补）。
 */
:deep(.pdf-viewport) {
  overflow-x: auto !important;
  overflow-y: auto !important;
}

.project-file-viewer {
  position: relative;
  box-sizing: border-box;
  width: 100%;
  height: 100%;
  min-width: 0;
  max-width: 100%;
  display: flex;
  flex-direction: column;
}

/*
 * 缩放工具栏（v1.5.2 → v1.5.3 移到顶部中间）
 *
 * 位置：顶部中间浮层（v1.5.3 用户偏好：从右下角移到 top-center）。
 *       - 视觉上更对称 / 不遮挡右下角内容
 *       - 与 image-toolbar / pdf-toolbar 视觉一致（半透明白底 + blur）
 *
 * 触发：仅当 renderer 上报 zoom-change 事件时（即图片 / PDF / 文本）显示；
 *       xlsx / word 等不支持 zoom 的格式自动隐藏。
 *
 * 注：top: 12px 是相对于 .project-file-viewer 容器顶部；
 * 抽屉自身的 header（filename + download + close）是父组件的 drawer #header slot，
 * 在 project-file-viewer 之外，所以工具栏不会盖到文件名。
 */
.preview-zoom-bar {
  position: absolute;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 6;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px 4px 4px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(148, 163, 184, 0.34);
  border-radius: 10px;
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.14);
  backdrop-filter: blur(12px);
  box-sizing: border-box;
}
.preview-zoom-label {
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--color-text-secondary, #475569);
  min-width: 42px;
  text-align: center;
  user-select: none;
}
.viewer-host {
  flex: 1;
  min-height: 0;
  min-width: 0;
  max-width: 100%;
  position: relative;
  overflow: hidden;
}
.preview-error {
  margin: 32px;
  padding: 16px;
  border: 1px solid var(--color-danger, #f56c6c);
  color: var(--color-danger, #f56c6c);
  border-radius: 4px;
  background: var(--color-bg, #fff);
}

/*
 * 2) 文本渲染器（@file-viewer/renderer-text）的内层 .code-area / .html-source-view /
 * .markdown-body 都声明了 overflow:auto，但 height 是 auto → 滚动条不会激活。
 * 根因：file-viewer core 注入的 .file-render 只设了 inline width/min-*
 * （rendererMode:'replace' + styleIsolation:'scoped' 下不会包 .file-viewer-web-shell
 * 那一层 flex column），下游 .code-viewer 等子元素拿不到高度约束。
 *
 * 修法：沿 viewer-host → file-render → code-viewer/html-viewer/markdown-viewer
 * 三层都强制成 flex column，让 height 沿 flex 链传下去；最里层 .code-area 等
 * 用 flex:1 占满剩余空间，自身的 overflow:auto 才会激活滚动条。
 *
 * 同时约束 max-width:100% / min-width:0，让 flex 链不会被子元素最小宽度
 * 撑大；pre/table/img 三个最易引起横向溢出的元素分别给：
 *   - pre/code   → overflow-x:auto（仅内部可滚）+ 长行折行
 *   - table      → table-layout:fixed + word-break
 *   - img        → max-width:100%
 */
.viewer-host :deep(.file-render) {
  height: 100%;
  min-height: 0;
  min-width: 0;
  max-width: 100%;
  display: flex;
  flex-direction: column;
}
.viewer-host :deep(.code-viewer),
.viewer-host :deep(.html-viewer),
.viewer-host :deep(.markdown-viewer),
.viewer-host :deep(.pdf-viewer),
.viewer-host :deep(.image-viewer) {
  height: 100%;
  min-height: 0;
  min-width: 0;
  max-width: 100%;
  display: flex;
  flex-direction: column;
}
/*
 * PDF 容器兜底（v1.5.1 v5）
 *
 * fit: 'width' resize: 'always' 是主修（动态重新 fit），
 * 但 PDF.js 在 .page 上 inline style="width: 1761px !important" 是
 * inline-important，特异性 = 1000 + selector，永远压 selector-important。
 * 所以这里不再尝试用 selector !important 覆盖 .page —— 改用对
 * 父容器 .pdfViewer 加 width:100% + overflow-x:hidden 双保险：
 *   - width:100% !important 让 .pdfViewer 不超过 .pdf-viewport 宽度
 *   - overflow-x:hidden 兜底裁掉任何极端溢出（页面文字会被裁一部分，
 *     用户点 toolbar 「适应宽度」按钮能恢复完整视图）
 */
.viewer-host :deep(.pdfViewer) {
  width: 100% !important;
  max-width: 100% !important;
  overflow-x: auto !important;
}
/*
 * PDF 工具条（toolbar 包含：上一页/下一页/缩放/页码/旋转），
 * 在小抽屉宽度（80% × <1200px 屏 或 浏览器放大 ≥ 125%）下按钮组会
 * 撑出横向滚动。强制 flex-wrap:wrap 让按钮换行，溢出风险归零。
 * @file-viewer 自带的 @media (max-width:720px) 只对 ≤720px 视口生效，
 * 抽屉宽度不在此范围内时不换行 —— 这是用户报告横滚的真凶之一。
 */
.viewer-host :deep(.pdf-toolbar) {
  flex-wrap: wrap !important;
  row-gap: 4px;
}
.viewer-host :deep(.pdfViewer) {
  box-sizing: border-box !important;
  max-width: 100% !important;
  overflow-x: auto !important;
}
.viewer-host :deep(.code-area) {
  flex: 1 1 0%;
  min-height: 0;
  min-width: 0;
  max-width: 100%;
}
.viewer-host :deep(.html-preview-frame),
.viewer-host :deep(.html-source-view) {
  flex: 1 1 0%;
  min-height: 0;
  min-width: 0;
  max-width: 100%;
}

/*
 * 3) 内容级别的横向溢出防御
 *  - 图片：缩放进容器，不超过 drawer 宽度
 *  - 表格：固定布局 + 拆列，避免单列宽行触发整表溢出
 *  - pre/code：允许内部水平滚动（深嵌套仍可读），但容器约束最大宽度
 *  - 段落/列表项：长 URL/长 token 折行（不破坏空格换行但拆超长连续字符）
 */
.viewer-host :deep(img) {
  max-width: 100%;
  height: auto;
}
.viewer-host :deep(table) {
  table-layout: fixed;
  width: 100%;
  max-width: 100%;
}
.viewer-host :deep(td),
.viewer-host :deep(th) {
  word-break: break-word;
  overflow-wrap: anywhere;
}
.viewer-host :deep(pre) {
  max-width: 100%;
  overflow-x: auto;
}
.viewer-host :deep(.markdown-body p),
.viewer-host :deep(.markdown-body li),
.viewer-host :deep(.code-area code) {
  overflow-wrap: anywhere;
  word-break: break-word;
}

/*
 * v1.5.2 末：默认 = 原始大小，不做 CSS 缩放（用户最终拍板）
 *
 * 不在 `.file-render` 上设 `zoom`。原因：
 *   1) `zoom` 是非标准 CSS 属性（Chrome/Safari/Edge OK，Firefox 126+ 才支持），
 *      跨浏览器一致性差。
 *   2) PDF 等 renderer 内部有 fit / scale 机制，CSS zoom 与之正交叠加，
 *      toolbar 显示的「64%」是 renderer 内部 scale，CSS zoom 不会改 toolbar
 *      数字，用户看 toolbar 容易误判「没生效」。
 *   3) 各 renderer 已经在 fit 阶段决定了自己的「原始大小」：
 *      - PDF：fit.mode:'actual' → scale=1（794×1123 @ 96 DPI for A4）
 *      - 代码 / JSON / YAML：renderer 默认 13px
 *      - Markdown：renderer 默认 16px
 *      - xlsx：renderer 默认 fit-width
 *      - 图片：原图尺寸（max-width:100% 兜底不超出抽屉）
 *    CSS 没必要再叠加一层。
 *
 * 后续若要统一缩放，建议走 renderer 自身的 fit / initialViewState 通道
 * （如 `initialViewState: { scale: 1.2 }` for PDF），而不是 CSS zoom。
 */
</style>

<style>
/*
 * 非 scoped 全局样式（v1.5.1 v4）。
 *
 * 原因：el-drawer 通过 <Teleport to="body"> 把 overlay/drawer 移出
 * ProjectFileViewer 的 DOM 树，挂在 <body> 直接子节点。它们是
 * ProjectFileViewer 的 **祖先** 而非后代 —— :deep() 只匹配后代，
 * 编译后的 [data-v-xxx] .el-overlay 永远不命中。
 *
 * 范围控制：本全局规则只挂"el-overlay/el-drawer/el-drawer__body
 * 三个祖先类 + overflow-x:hidden 这一条规则"，scope 极小。
 * 全局污染风险 ≈ 0（el-overlay 仅 el-drawer/dialog/drawer 使用，
 * 都不会受 overflow-x:hidden 影响 —— 它们内容自己处理水平滚动）。
 */
.el-overlay,
.el-drawer,
.el-drawer__body {
  overflow-x: hidden !important;
}
</style>