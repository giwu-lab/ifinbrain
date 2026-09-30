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

import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { Download } from '@element-plus/icons-vue';
import { createViewer, setDefaultFileViewerAssetBaseUrl } from '@file-viewer/core/browser';
import { officeRenderers } from '@file-viewer/preset-office';
import { imageRenderer } from '@file-viewer/renderer-image';
import { textRenderer } from '@file-viewer/renderer-text';
import { enableFileViewerTextTools } from '@file-viewer/capability-text-tools';
import type { FileViewerLifecycleContext, FileViewerOperationContext } from '@file-viewer/core';
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
      options: {
        builtinRenderers: 'none',
        rendererMode: 'replace',
        // preset-office 8 个 renderer + image + text；capability-text-tools 已在
        // 模块加载时通过 enableFileViewerTextTools() 注册到 textRenderer 内部。
        // 强转 any：officeRenderers / imageRenderer / textRenderer 的 Handler 泛型
        // 不同，TS 静态无法对账；file-viewer runtime 接受任意 FileRenderHandler。
        renderers: [...officeRenderers.renderers, imageRenderer, textRenderer] as any,
        styleIsolation: 'scoped',
        toolbar: { position: 'bottom-right' },
        // 强制亮色主题：不随 OS / 浏览器 prefers-color-scheme 切换
        theme: 'light',
        // PDF 抽屉渲染：fit:'width' + resize:'always'（v1.5.1 v5）
        //
        // 上一版 resize:'initial' 只在首次加载 fit 一次。结果抽屉打开时
        // PDF.js 测量到的 .pdf-wrapper 宽 = 父元素 transition 动画过程中的
        // 旧宽度（≈1920px 全屏），等到抽屉动画完成 fit 已经跑完，
        // scale 被钉死在 296%，.page 被 inline style="width: 1761px !important"
        // 撑出 .pdf-wrapper 的横向滚动条。
        //
        // 改 resize:'always'：每次 .pdf-wrapper 尺寸变化（抽屉打开动画、
        // 拖拽改 size、浏览器缩放）都触发 fit 重新测量，永远 fit 当前
        // 真实可视宽度。minScale/maxScale 防止极端值（用户 222% 后 fit
        // 不会跳到 50% 或 400%，保留最小 0.5×/最大 3× 的合理区间）。
        // padding:8 给 PDF 边距留 8px，避免贴边。
        fit: { mode: 'width', resize: 'always', padding: 8, minScale: 0.5, maxScale: 3 },
        pdf: {
          // fit 优先；initialViewState.scale 与 fit 互斥，留空
        },
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

onMounted(load);
watch(() => props.storageKey, load);
onBeforeUnmount(destroyViewer);
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
:deep(.pdf-viewport),
:deep(.pdf-wrapper) {
  overflow-x: hidden !important;
}

.project-file-viewer {
  box-sizing: border-box;
  width: 100%;
  height: 100%;
  min-width: 0;
  max-width: 100%;
  display: flex;
  flex-direction: column;
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
  overflow-x: hidden !important;
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
  overflow-x: hidden !important;
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