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
import type { FileViewerLifecycleContext, FileViewerOperationContext } from '@file-viewer/core';
import { fileRawUrl } from '@/api/document';

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
        renderers: officeRenderers,
        styleIsolation: 'scoped',
        toolbar: { position: 'bottom-right' },
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
.project-file-viewer {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.viewer-host {
  flex: 1;
  min-height: 0;
  position: relative;
}
.preview-error {
  margin: 32px;
  padding: 16px;
  border: 1px solid var(--color-danger, #f56c6c);
  color: var(--color-danger, #f56c6c);
  border-radius: 4px;
  background: var(--color-bg, #fff);
}
</style>