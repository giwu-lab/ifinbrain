<script setup lang="ts">
/**
 * FilePreviewer —— 多格式文件预览分发器
 *  - image    : <el-image> + 缩放（Element Plus 原生）
 *  - pdf      : PdfViewer（PDF.js + 分页 / 缩放 / 搜索）
 *  - xml      : 格式化 XML（pre 块，可选 Monaco）
 *  - code-text: Monaco 只读（txt / csv / json / md / sql / yml 等）
 *  - docx     : mammoth → HTML
 *  - xlsx     : SheetJS → <el-table> 多 sheet
 *  - audio    : <audio>
 *  - video    : <video>
 *  - ofd/unsupported : 元信息 + 下载按钮
 *
 * 设计要点：
 *  1. mammoth / xlsx / PdfViewer 全部按需动态 import —— 用户进预览才下载对应 chunk
 *  2. fetch() 拿 blob（不走 axios，避开已知 blob 解析 bug）
 *  3. 父组件传 storageKey + filename，本组件分发
 */

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import DOMPurify from 'dompurify';
import { ElMessage } from 'element-plus';
import { Download } from '@element-plus/icons-vue';
import { fileRawUrl } from '@/api/document';
import { detectKind, detectMonacoLang, type PreviewKind } from '@/utils/fileType';
import {
  MONACO_WORKER_URL,
  MONACO_JSON_WORKER_URL,
  MONACO_CSS_WORKER_URL,
  MONACO_HTML_WORKER_URL,
  MONACO_TS_WORKER_URL,
} from '@/utils/workers';
import PdfViewer from './PdfViewer.vue';

declare const self: { MonacoEnvironment?: any };

const props = defineProps<{
  storageKey: string;
  filename: string;
}>();

const emit = defineEmits<{
  (e: 'download', row: { storageKey: string; name: string }): void;
}>();

const { t } = useI18n();

const kind = computed<PreviewKind>(() => detectKind(props.filename));
const loading = ref(true);
const errorMsg = ref('');

// 不同渲染器的状态
const blobUrl = ref<string>('');          // image / audio / video
const textContent = ref<string>('');      // xml / code-text
const docxHtml = ref<string>('');         // docx
const xlsxData = ref<{ sheets: string[]; active: number; rows: Record<string, any>[]; cols: string[] }>({
  sheets: [], active: 0, rows: [], cols: [],
});
const meta = ref<{ size: number }>({ size: 0 });

// Monaco 编辑器：动态 import + 引用
let monacoEditor: any = null;
const monacoContainerRef = ref<HTMLElement | null>(null);

/** Monaco worker 装载 —— 按 label 分派到对应语言 worker */
function ensureMonacoWorker() {
  self.MonacoEnvironment = self.MonacoEnvironment || {
    getWorker(_workerId: string, label: string) {
      if (label === 'json') return new Worker(MONACO_JSON_WORKER_URL, { type: 'module' });
      if (label === 'css' || label === 'scss' || label === 'less') return new Worker(MONACO_CSS_WORKER_URL, { type: 'module' });
      if (label === 'html' || label === 'handlebars' || label === 'razor') return new Worker(MONACO_HTML_WORKER_URL, { type: 'module' });
      if (label === 'typescript' || label === 'javascript') return new Worker(MONACO_TS_WORKER_URL, { type: 'module' });
      return new Worker(MONACO_WORKER_URL, { type: 'module' });
    },
  };
}

function disposeMonaco() {
  try {
    if (monacoEditor) {
      monacoEditor.dispose();
      monacoEditor = null;
    }
  } catch { /* ignore */ }
}

async function ensureMonaco() {
  if (monacoEditor) return monacoEditor;
  const monaco = await import('monaco-editor');
  ensureMonacoWorker();
  if (!monacoContainerRef.value) return null;
  monacoEditor = monaco.editor.create(monacoContainerRef.value, {
    value: textContent.value,
    language: detectMonacoLang(props.filename),
    readOnly: true,
    automaticLayout: true,
    fontSize: 13,
    minimap: { enabled: false },
    scrollBeyondLastLine: false,
    wordWrap: 'on',
    theme: 'vs',
  });
  return monacoEditor;
}

async function setupMonaco() {
  const editor = await ensureMonaco();
  if (editor && textContent.value !== editor.getValue()) {
    editor.setValue(textContent.value);
  }
}

// ===================== 加载与分发 =====================
async function load() {
  loading.value = true;
  errorMsg.value = '';
  disposeMonaco();
  if (blobUrl.value) {
    URL.revokeObjectURL(blobUrl.value);
    blobUrl.value = '';
  }
  textContent.value = '';
  docxHtml.value = '';
  xlsxData.value = { sheets: [], active: 0, rows: [], cols: [] };
  meta.value = { size: 0 };

  const k = kind.value;
  try {
    if (k === 'image' || k === 'audio' || k === 'video') {
      const blob = await fetchBlob();
      blobUrl.value = URL.createObjectURL(blob);
      meta.value = { size: blob.size };
    } else if (k === 'pdf') {
      // PdfViewer 自己处理加载
    } else if (k === 'xml') {
      const blob = await fetchBlob();
      const text = await blob.text();
      textContent.value = formatXml(text);
      meta.value = { size: blob.size };
    } else if (k === 'code-text') {
      const blob = await fetchBlob();
      const text = await blob.text();
      textContent.value = text;
      meta.value = { size: blob.size };
      // Monaco 在模板里 v-if 渲染，这里等 DOM 就绪
      setTimeout(setupMonaco, 50);
    } else if (k === 'docx') {
      const blob = await fetchBlob();
      // mammoth.browser 没有自带类型 —— 用 @ts-ignore 绕过
      // @ts-ignore
      const mammoth = await import('mammoth/mammoth.browser');
      const result = await mammoth.convertToHtml({ arrayBuffer: await blob.arrayBuffer() });
      docxHtml.value = sanitizeDocxHtml(result.value);
      meta.value = { size: blob.size };
    } else if (k === 'xlsx') {
      const blob = await fetchBlob();
      const XLSX = await import('xlsx');
      const wb = XLSX.read(await blob.arrayBuffer(), { type: 'array' });
      const sheets = wb.SheetNames;
      xlsxData.value.sheets = sheets;
      xlsxData.value.active = 0;
      parseXlsxSheet(XLSX, wb, sheets[0]);
      meta.value = { size: blob.size };
    } else {
      // ofd / unsupported —— 只取 size
      const blob = await fetchBlob();
      meta.value = { size: blob.size };
    }
  } catch (e: any) {
    errorMsg.value = e?.message || t('documents.previewError');
  } finally {
    loading.value = false;
  }
}

function parseXlsxSheet(XLSX: any, wb: any, sheetName: string) {
  const ws = wb.Sheets[sheetName];
  const json: Record<string, any>[] = XLSX.utils.sheet_to_json(ws, { defval: '' });
  const cols = json.length > 0 ? Object.keys(json[0]) : [];
  xlsxData.value.rows = json;
  xlsxData.value.cols = cols;
  xlsxData.value.active = xlsxData.value.sheets.indexOf(sheetName);
}

/**
 * mammoth 输出的是任意 docx 内容渲染出的 HTML，可能含 <script> / <iframe> 等危险标签。
 * DOMPurify 默认配置即可剥离 script/on* 属性/javascript: URL，同时保留富文本样式。
 * v-html 只接受 sanitize 后的字符串，避免 XSS（用户上传恶意 docx 偷 localStorage token）。
 */
function sanitizeDocxHtml(raw: string): string {
  return DOMPurify.sanitize(raw, {
    USE_PROFILES: { html: true },
  });
}

function switchXlsxSheet(idx: number) {
  import('xlsx').then(XLSX => {
    // 简易版：再次 fetch blob（实际可以缓存 wb）
    // 为了简化代码，重新 read
    fetchBlob().then(async blob => {
      const wb = XLSX.read(await blob.arrayBuffer(), { type: 'array' });
      parseXlsxSheet(XLSX, wb, xlsxData.value.sheets[idx]);
    });
  });
}

async function fetchBlob(): Promise<Blob> {
  const token = localStorage.getItem('accessToken') || '';
  const resp = await fetch(fileRawUrl(props.storageKey, false), {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!resp.ok) {
    throw new Error(`HTTP ${resp.status}`);
  }
  return resp.blob();
}

/** 简单 XML 格式化（缩进）—— 仅用于可读性展示，不是严格解析 */
function formatXml(xml: string): string {
  let formatted = '';
  let indent = 0;
  const tab = '  ';
  xml.split(/>\s*</).forEach((node) => {
    if (node.match(/^\/\w/)) indent--;          // 闭合标签
    formatted += tab.repeat(Math.max(0, indent)) + '<' + node + '>\n';
    if (node.match(/^<?\w[^>]*[^\/]$/) && !node.startsWith('?')) indent++;
  });
  return formatted.trim();
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

function onDownload() {
  emit('download', { storageKey: props.storageKey, name: props.filename });
}

function copyText() {
  if (!textContent.value) return;
  navigator.clipboard.writeText(textContent.value).then(
    () => ElMessage.success(t('documents.previewCopySuccess')),
    () => ElMessage.error(t('documents.previewCopyFail')),
  );
}

onMounted(load);
watch(() => props.storageKey, load);

// 卸载时清理
onBeforeUnmount(() => {
  disposeMonaco();
  if (blobUrl.value) URL.revokeObjectURL(blobUrl.value);
});

const ext = computed(() => {
  const n = props.filename.split('?')[0];
  return (n.split('.').pop() || '').toLowerCase();
});
</script>

<template>
  <div class="file-previewer">
    <el-skeleton v-if="loading" :rows="4" animated />

    <template v-else>
      <div v-if="errorMsg" class="preview-error">{{ errorMsg }}</div>

      <!-- 图像 -->
      <div v-else-if="kind === 'image'" class="preview-block image-block">
        <el-image
          :src="blobUrl"
          :preview-src-list="[blobUrl]"
          :title="filename"
          fit="contain"
          class="preview-image"
        />
      </div>

      <!-- PDF —— PDF.js -->
      <PdfViewer
        v-else-if="kind === 'pdf'"
        :storage-key="storageKey"
        :filename="filename"
        @download="onDownload"
      />

      <!-- XML —— 格式化 pre -->
      <div v-else-if="kind === 'xml'" class="preview-block xml-block">
        <div class="block-toolbar">
          <span class="muted small">{{ filename }} · {{ formatSize(meta.size) }}</span>
          <el-button size="small" @click="onDownload">
            <el-icon><Download /></el-icon>{{ t('common.download') }}
          </el-button>
        </div>
        <pre class="xml-pre">{{ textContent }}</pre>
      </div>

      <!-- 文本/代码 —— Monaco 只读 -->
      <div v-else-if="kind === 'code-text'" class="preview-block code-block">
        <div class="block-toolbar">
          <span class="muted small">{{ filename }} · {{ formatSize(meta.size) }} · {{ ext }}</span>
          <el-button size="small" @click="copyText">{{ t('documents.previewCopied') }}</el-button>
          <el-button size="small" @click="onDownload">
            <el-icon><Download /></el-icon>{{ t('common.download') }}
          </el-button>
        </div>
        <div ref="monacoContainerRef" class="monaco-host"></div>
      </div>

      <!-- DOCX —— mammoth HTML -->
      <div v-else-if="kind === 'docx'" class="preview-block docx-block">
        <div class="block-toolbar">
          <span class="muted small">{{ filename }} · {{ formatSize(meta.size) }}</span>
          <el-button size="small" @click="onDownload">
            <el-icon><Download /></el-icon>{{ t('common.download') }}
          </el-button>
        </div>
        <div class="docx-content" v-html="docxHtml"></div>
      </div>

      <!-- XLSX —— SheetJS 多 sheet -->
      <div v-else-if="kind === 'xlsx'" class="preview-block xlsx-block">
        <div class="block-toolbar">
          <span class="muted small">{{ filename }} · {{ formatSize(meta.size) }}</span>
          <el-button size="small" @click="onDownload">
            <el-icon><Download /></el-icon>{{ t('common.download') }}
          </el-button>
        </div>
        <div v-if="xlsxData.sheets.length > 1" class="sheet-tabs">
          <el-radio-group v-model="xlsxData.active" @change="(val: any) => switchXlsxSheet(Number(val))">
            <el-radio-button
              v-for="(s, idx) in xlsxData.sheets"
              :key="s"
              :value="idx"
            >{{ s }}</el-radio-button>
          </el-radio-group>
        </div>
        <el-table :data="xlsxData.rows" stripe border size="small" max-height="calc(100vh - 240px)">
          <el-table-column
            v-for="col in xlsxData.cols"
            :key="col"
            :prop="col"
            :label="col"
            min-width="120"
            show-overflow-tooltip
          />
        </el-table>
        <div v-if="xlsxData.rows.length === 0" class="muted small xlsx-empty">{{ t('documents.previewXlsxEmpty') }}</div>
      </div>

      <!-- 音视频 -->
      <div v-else-if="kind === 'audio'" class="preview-block media-block">
        <audio :src="blobUrl" controls class="media-el" />
        <div class="muted small">{{ filename }} · {{ formatSize(meta.size) }}</div>
      </div>
      <div v-else-if="kind === 'video'" class="preview-block media-block">
        <video :src="blobUrl" controls class="media-el" />
        <div class="muted small">{{ filename }} · {{ formatSize(meta.size) }}</div>
      </div>

      <!-- OFD / 不支持 —— 提示 + 下载 -->
      <div v-else class="preview-block unsupported-block">
        <div class="empty-icon"><el-icon style="font-size: 48px;"><Download /></el-icon></div>
        <h3 class="empty-title">{{ t('documents.previewUnsupported') }}</h3>
        <p class="empty-desc muted">{{ t('documents.previewDownloadHint') }}</p>
        <el-button type="primary" @click="onDownload">
          <el-icon><Download /></el-icon>{{ t('documents.previewDownloadBtn') }}
        </el-button>
        <div class="muted small mt-8">{{ filename }} · {{ formatSize(meta.size) }}</div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.file-previewer {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.preview-block {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.block-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 12px;
  border-bottom: 1px solid var(--color-border-soft, #ebeef5);
  background: var(--color-bg-soft, #fafaf7);
  gap: 8px;
  flex-wrap: wrap;
}
.muted { color: var(--color-text-secondary, #6b6b65); }
.small { font-size: 12px; }
.mt-8 { margin-top: 8px; }

.image-block .preview-image {
  width: 100%;
  flex: 1;
  object-fit: contain;
  background: #f0f1f4;
}

.xml-block .xml-pre {
  flex: 1;
  margin: 0;
  padding: 12px;
  background: #fafaf7;
  font-family: 'SF Mono', Menlo, ui-monospace, monospace;
  font-size: 12px;
  line-height: 1.5;
  overflow: auto;
  white-space: pre;
  color: #1a1a1a;
}

.code-block .monaco-host {
  flex: 1;
  min-height: 0;
}

.docx-block .docx-content {
  flex: 1;
  padding: 24px 32px;
  overflow: auto;
  background: #fff;
  line-height: 1.7;
  font-size: 14px;
}
.docx-block .docx-content :deep(h1) { font-size: 22px; font-weight: 700; margin: 16px 0 8px; }
.docx-block .docx-content :deep(h2) { font-size: 18px; font-weight: 600; margin: 14px 0 6px; }
.docx-block .docx-content :deep(p) { margin: 8px 0; }
.docx-block .docx-content :deep(table) { border-collapse: collapse; margin: 12px 0; }
.docx-block .docx-content :deep(td),
.docx-block .docx-content :deep(th) { border: 1px solid var(--color-border, #e4e7ed); padding: 6px 12px; }

.xlsx-block .sheet-tabs {
  padding: 8px 12px 0;
}
.xlsx-block .xlsx-empty {
  padding: 24px;
  text-align: center;
}

.media-block {
  align-items: center;
  justify-content: center;
  padding: 24px;
  gap: 12px;
}
.media-el {
  max-width: 100%;
  max-height: 70vh;
  outline: 1px solid var(--color-border-soft, #ebeef5);
}

.unsupported-block {
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 64px 24px;
  gap: 12px;
}
.unsupported-block .empty-icon { color: var(--color-text-secondary, #909399); }

.preview-error {
  margin: 32px;
  padding: 16px;
  border: 1px solid var(--color-danger, #f56c6c);
  color: var(--color-danger, #f56c6c);
  border-radius: 4px;
  background: #fff;
}
</style>