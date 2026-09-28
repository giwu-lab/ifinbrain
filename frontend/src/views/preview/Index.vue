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
 *  - per-archive view perm 由 Preview view 内 extractSlug() + hasPerm('documents:'+slug+':view') 校验；
 *    后端 FileController.raw() 同步守住（深度防御）
 *  - 全屏布局：顶部极简 header（关闭 + 下载 + 文件名），body 整个给 ProjectFileViewer
 *  - 不挂 router meta.permission —— 否则没全局 documents:view 但有 documents:{slug}:view 的用户会被弹 /403
 */

import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { Close, Download, Back } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import ProjectFileViewer from '@/components/ProjectFileViewer.vue';
import { fileRawUrl } from '@/api/document';

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const storageKey = computed(() => String(route.query.key || ''));
const filename = computed(() => String(route.query.name || ''));

/** key 格式：documents/{slug}/{rest}；非 documents/ 前缀（如 invoice 的 OFD）→ 视为无权限 */
const DOC_KEY_RE = /^documents\/([^/]+)(?:\/.*)?$/;
const archiveSlug = computed(() => {
  const m = DOC_KEY_RE.exec(storageKey.value);
  return m?.[1] || null;
});

const permChecked = ref(false);
const hasViewPerm = ref(false);

function recheckPerm() {
  const slug = archiveSlug.value;
  if (!slug) {
    // 非 documents/ 前缀：/preview 只为档案库设计；invoice 文件保持 drawer 体验，不进这条
    hasViewPerm.value = false;
    permChecked.value = true;
    return;
  }
  hasViewPerm.value = auth.hasPerm(`documents:${slug}:view`);
  permChecked.value = true;
}
// 监听 storageKey + permissions（admin 后台改 perm 后新 tab 自动重算；配合 refreshIfStale）
watch(() => [storageKey.value, auth.user?.permissions], recheckPerm, { immediate: true });

function backToList() {
  const slug = archiveSlug.value;
  if (slug) router.push(`/documents/${slug}`);
  else router.push('/documents/archive');
  // router.push 跳原 tab（不会自动关当前预览 tab）；用户可手动关，或触发 history.go(-1)
  setTimeout(() => window.close(), 300);
}

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
        <template v-if="!permChecked || hasViewPerm">
          <el-button :icon="Download" @click="onDownload">{{ t('common.download') }}</el-button>
          <el-button :icon="Close" @click="onClose">{{ t('common.close') }}</el-button>
        </template>
        <el-button v-else :icon="Back" @click="backToList">
          {{ t('preview.backToList') }}
        </el-button>
      </div>
    </header>
    <main class="preview-body">
      <el-empty
        v-if="permChecked && !hasViewPerm"
        :description="t('preview.noPermission')"
        :image-size="120"
      />
      <ProjectFileViewer
        v-else-if="hasViewPerm && storageKey"
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