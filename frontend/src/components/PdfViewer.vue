<script setup lang="ts">
// PDF.js worker 装载委托给 utils/pdfWorker.ts 的单例 ensurePdfWorkerReady()，
// load() 入口 await 它，保证 GlobalWorkerOptions.workerPort 已就位再 getDocument()。
// （workerSrc URL 在 Vite dev 会被注入 /@vite/client import，worker 启动失败 → hang）
import * as pdfjsLib from 'pdfjs-dist';
import { ensurePdfWorkerReady } from '@/utils/pdfWorker';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { ArrowLeft, ArrowRight, Download, ZoomIn, ZoomOut } from '@element-plus/icons-vue';
import { fileRawUrl } from '@/api/document';

const props = defineProps<{
  storageKey: string;
  filename: string;
}>();

const emit = defineEmits<{ (e: 'download', row: { storageKey: string; originalFilename?: string; name: string }): void }>();

const { t } = useI18n();

const canvasRef = ref<HTMLCanvasElement | null>(null);
const canvasWrapRef = ref<HTMLDivElement | null>(null);
const loading = ref(true);
const errorMsg = ref('');
const pageNum = ref(1);
const totalPages = ref(0);
const scale = ref(1.50);
const query = ref('');
const matchCount = ref(0);
const activeMatchIdx = ref(0);
const showSearch = ref(false);

let pdfDoc: any = null;
let renderTask: any = null;
let highlightTask: any = null;

// ===================== 加载 =====================
async function load() {
  loading.value = true;
  errorMsg.value = '';
  pageNum.value = 1;
  totalPages.value = 0;
  matchCount.value = 0;
  if (renderTask) { try { renderTask.cancel(); } catch {} renderTask = null; }
  if (highlightTask) { try { highlightTask.cancel(); } catch {} highlightTask = null; }

  try {
    // 关键：先 await worker 就位再 getDocument()，否则 PDF.js 会走 workerSrc fallback
    // → Vite dev 在 URL 里注入 /@vite/client import → worker 启动失败 → hang
    await ensurePdfWorkerReady();
    // 用 fetch（避免 axios blob bug — 之前已经验证 axios 对 blob 响应处理有 bug）
    const token = localStorage.getItem('accessToken') || '';
    const resp = await fetch(fileRawUrl(props.storageKey, false), {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!resp.ok) {
      errorMsg.value = t('documents.previewError') + `: HTTP ${resp.status}`;
      return;
    }
    const data = await resp.arrayBuffer();
    const loadingTask = pdfjsLib.getDocument({ data });
    pdfDoc = await loadingTask.promise;
    totalPages.value = pdfDoc.numPages;
    await renderPage(1);
  } catch (e: any) {
    // PDF.js 抛 InvalidPDFException 等
    if (e?.name === 'PasswordException') {
      errorMsg.value = t('documents.previewPdfEncrypted');
    } else {
      errorMsg.value = e?.message || t('documents.previewError');
    }
    pdfDoc = null;
  } finally {
    loading.value = false;
  }
}

// ===================== 渲染单页 =====================
async function renderPage(n: number) {
  if (!pdfDoc || !canvasRef.value) return;
  if (renderTask) { try { renderTask.cancel(); } catch {} renderTask = null; }
  pageNum.value = n;
  const page = await pdfDoc.getPage(n);
  const viewport = page.getViewport({ scale: scale.value });
  const canvas = canvasRef.value;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  // 高分屏适配
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.floor(viewport.width * dpr);
  canvas.height = Math.floor(viewport.height * dpr);
  canvas.style.width = `${viewport.width}px`;
  canvas.style.height = `${viewport.height}px`;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  renderTask = page.render({ canvasContext: ctx, viewport });
  await renderTask.promise;
  // 渲染后重新应用搜索高亮
  if (query.value) await runFind(false);
}

// ===================== 翻页 / 缩放 =====================
function prevPage() {
  if (pageNum.value > 1) renderPage(pageNum.value - 1);
}
function nextPage() {
  if (pageNum.value < totalPages.value) renderPage(pageNum.value + 1);
}
function zoomIn() {
  scale.value = Math.min(scale.value + 0.25, 4);
  renderPage(pageNum.value);
}
function zoomOut() {
  scale.value = Math.max(scale.value - 0.25, 0.25);
  renderPage(pageNum.value);
}
async function fitWidth() {
  if (!pdfDoc || !canvasWrapRef.value) return;
  const page = await pdfDoc.getPage(pageNum.value);
  const viewport = page.getViewport({ scale: 1 });
  const wrapWidth = canvasWrapRef.value.clientWidth - 32; // padding
  scale.value = Math.max(0.25, wrapWidth / viewport.width);
  renderPage(pageNum.value);
}
async function fitPage() {
  if (!pdfDoc || !canvasWrapRef.value) return;
  const page = await pdfDoc.getPage(pageNum.value);
  const viewport = page.getViewport({ scale: 1 });
  const wrapWidth = canvasWrapRef.value.clientWidth - 32;
  const wrapHeight = canvasWrapRef.value.clientHeight - 32;
  scale.value = Math.max(0.25,
    Math.min(wrapWidth / viewport.width, wrapHeight / viewport.height));
  renderPage(pageNum.value);
}

// ===================== 文本搜索（PDF.js 内置 PDFFindController） =====================
async function runFind(reset = true) {
  if (!pdfDoc || !query.value) return;
  if (highlightTask) { try { highlightTask.cancel(); } catch {} highlightTask = null; }
  if (reset) activeMatchIdx.value = 0;
  // PDFFindController 是 PDF.js 内置的事件驱动搜索器
  // 这里用简单实现：手动搜索当前页 + 后续页，记录匹配总数
  matchCount.value = 0;
  const q = query.value.toLowerCase();
  const numPages = pdfDoc.numPages;
  for (let p = 1; p <= numPages; p++) {
    const page = await pdfDoc.getPage(p);
    const textContent = await page.getTextContent();
    const pageText = textContent.items.map((it: any) => it.str).join(' ').toLowerCase();
    const matches = pageText.split(q).length - 1;
    if (matches > 0) {
      matchCount.value += matches;
      if (reset && p >= pageNum.value && activeMatchIdx.value === 0 && p > pageNum.value) {
        activeMatchIdx.value += matches;
        if (pageNum.value !== p) renderPage(p);
        return;
      }
    }
  }
  if (matchCount.value === 0) ElMessage.info(t('documents.previewSearchNoMatch'));
}

function openSearch() {
  showSearch.value = true;
  nextTick(() => searchInputRef.value?.focus());
}
function closeSearch() {
  showSearch.value = false;
  query.value = '';
  matchCount.value = 0;
}
const searchInputRef = ref<HTMLInputElement | null>(null);

// ===================== 下载原文件 =====================
function onDownload() {
  emit('download', { storageKey: props.storageKey, name: props.filename });
}

// ===================== 生命周期 =====================
onMounted(load);
watch(() => props.storageKey, load);
onBeforeUnmount(() => {
  if (renderTask) { try { renderTask.cancel(); } catch {} }
  if (highlightTask) { try { highlightTask.cancel(); } catch {} }
  if (pdfDoc) { try { pdfDoc.destroy(); } catch {} }
});

const zoomLabel = computed(() => `${Math.round(scale.value * 100)}%`);
const pageLabel = computed(() => totalPages.value > 0 ? `${pageNum.value} / ${totalPages.value}` : '—');
</script>

<template>
  <div class="pdf-viewer">
    <div class="pdf-toolbar">
      <el-button-group>
        <el-button :disabled="pageNum <= 1" @click="prevPage" :title="t('documents.previewPdfPrev')">
          <el-icon><ArrowLeft /></el-icon>
        </el-button>
        <el-button class="page-info" disabled>{{ pageLabel }}</el-button>
        <el-button :disabled="pageNum >= totalPages" @click="nextPage" :title="t('documents.previewPdfNext')">
          <el-icon><ArrowRight /></el-icon>
        </el-button>
      </el-button-group>
      <el-button-group>
        <el-button @click="zoomOut" :title="t('documents.previewZoomOut')"><el-icon><ZoomOut /></el-icon></el-button>
        <el-button class="zoom-info" disabled>{{ zoomLabel }}</el-button>
        <el-button @click="zoomIn" :title="t('documents.previewZoomIn')"><el-icon><ZoomIn /></el-icon></el-button>
      </el-button-group>
      <el-button-group>
        <el-button @click="fitWidth">{{ t('documents.previewPdfFitWidth') }}</el-button>
        <el-button @click="fitPage">{{ t('documents.previewPdfFitPage') }}</el-button>
      </el-button-group>
      <el-button-group>
        <el-button v-if="!showSearch" @click="openSearch">{{ t('documents.previewSearchBtn') }}</el-button>
        <template v-else>
          <el-input
            ref="searchInputRef"
            v-model="query"
            :placeholder="t('documents.previewSearchPlaceholder')"
            size="default"
            clearable
            class="pdf-search-input"
            @keyup.enter="runFind"
            @clear="matchCount = 0"
          />
          <el-button>{{ matchCount }}</el-button>
          <el-button @click="closeSearch">×</el-button>
        </template>
      </el-button-group>
      <el-button-group>
        <el-button @click="onDownload">
          <el-icon><Download /></el-icon>{{ t('common.download') }}
        </el-button>
      </el-button-group>
    </div>
    <div ref="canvasWrapRef" class="pdf-canvas-wrap">
      <el-skeleton v-if="loading" :rows="5" animated />
      <div v-else-if="errorMsg" class="pdf-error">{{ errorMsg }}</div>
      <canvas v-show="!loading && !errorMsg" ref="canvasRef" class="pdf-canvas" />
    </div>
  </div>
</template>

<style scoped>
.pdf-viewer {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--color-bg-soft, #f5f7fb);
}
.pdf-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--color-bg, #fff);
  border-bottom: 1px solid var(--color-border, #e4e7ed);
  flex-wrap: wrap;
}
.pdf-toolbar .page-info,
.pdf-toolbar .zoom-info {
  min-width: 64px;
  pointer-events: none;
}
.pdf-search-input {
  width: 200px;
}
.pdf-canvas-wrap {
  flex: 1;
  overflow: auto;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 16px;
  background: #e9eaee;
}
.pdf-canvas {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  background: #fff;
}
.pdf-error {
  margin: 48px auto;
  padding: 24px;
  background: var(--color-bg, #fff);
  border: 1px solid var(--color-danger, #f56c6c);
  border-radius: 4px;
  color: var(--color-danger, #f56c6c);
}
</style>