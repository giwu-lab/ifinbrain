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
        // PDF 默认 150% 缩放；不调用 fitToWidth —— 用户可手动点 toolbar 适应宽度
        pdf: {
          initialViewState: { scale: 1.5 },
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
 * 1) drawer 横向滚动条抑制（v1.5.1）
 * Element Plus 的 .el-drawer__body 默认 overflow:auto + 内容 padding，
 * 当 .file-render / .markdown-body / pre / table 等 inner 节点宽度
 * 超过 drawer 宽度时（图片原始尺寸、pre 长行、markdown 代码块、
 * 表格不可拆列），body 会出现横向滚动条。这里强制 overflow-x:hidden，
 * 让宽内容由 inner 元素自身消化（图片 max-width:100% / 长行折行）。
 */
:deep(.el-drawer__body) {
  overflow-x: hidden;
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