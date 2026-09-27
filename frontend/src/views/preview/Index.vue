<script setup lang="ts">
/**
 * Preview —— 档案库文件独立 tab 预览（替代旧 el-drawer）
 *
 * URL: /preview?key=<storageKey>&name=<originalName>
 *
 * 设计：
 *  - 取代 documents/list/Index.vue 里的 <el-drawer> 弹层；改为新 tab 打开
 *  - 文件元信息 (key / name) 走 URL query（不持久化、不进 store）
 *  - JWT 走 localStorage，新 tab 与原 tab 同源 → 共享 token
 *  - per-archive view perm 由 FileController.raw() 守住
 *  - 全屏布局：顶部极简 header（关闭 + 下载 + 文件名），body 整个给 ProjectFileViewer
 */

import { computed, onBeforeUnmount, ref } from 'vue';
import { useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { Close, Download } from '@element-plus/icons-vue';
import ProjectFileViewer from '@/components/ProjectFileViewer.vue';
import { fileRawUrl } from '@/api/document';

const { t } = useI18n();
const route = useRoute();

const storageKey = computed(() => String(route.query.key || ''));
const filename = computed(() => String(route.query.name || ''));

const blobUrl = ref<string>('');

async function onDownload() {
  if (!storageKey.value) return;
  try {
    const token = localStorage.getItem('accessToken') || '';
    const resp = await fetch(fileRawUrl(storageKey.value, true), {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const blob = await resp.blob();
    blobUrl.value = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = blobUrl.value;
    a.download = filename.value;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(blobUrl.value), 30_000);
  } catch {
    /* http interceptor 等兜底 */
  }
}

function onClose() {
  window.close();
}

onBeforeUnmount(() => {
  if (blobUrl.value) URL.revokeObjectURL(blobUrl.value);
});
</script>

<template>
  <div class="preview-page">
    <header class="preview-header">
      <div class="preview-name" :title="filename">{{ filename || t('documents.title') }}</div>
      <div class="preview-actions">
        <el-button :icon="Download" @click="onDownload">{{ t('common.download') }}</el-button>
        <el-button :icon="Close" @click="onClose">{{ t('common.close') }}</el-button>
      </div>
    </header>
    <main class="preview-body">
      <ProjectFileViewer
        v-if="storageKey"
        :storage-key="storageKey"
        :filename="filename"
      />
    </main>
  </div>
</template>

<style scoped>
.preview-page {
  position: fixed;
  inset: 0;
  display: flex;
  flex-direction: column;
  background: var(--color-bg);
}
.preview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 48px;
  padding: 0 16px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-bg-elev);
  flex-shrink: 0;
  gap: 16px;
}
.preview-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--color-text);
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: 'SF Mono', Menlo, monospace;
}
.preview-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.preview-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
</style>