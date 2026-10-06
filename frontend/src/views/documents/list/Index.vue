<template>
  <div class="page-container fade-in-up docs-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">
          <el-icon v-if="category" class="title-icon">
            <component :is="resolveIcon(category.icon)" />
          </el-icon>
          {{ category ? t('documents.listTitle', { name: category.name }) : t('documents.title') }}
        </h1>
        <p class="page-desc muted">
          <span v-if="category">/{{ category.slug }}</span>
          <span v-else>{{ t('documents.desc') }}</span>
        </p>
      </div>
      <div class="header-actions">
        <!-- RAG 问答：后端 RAG 关闭时整组端点不存在，探测失败即隐藏入口 -->
        <el-button
          v-if="ragEnabled"
          :icon="MagicStick"
          @click="ragVisible = true"
        >
          {{ t('documents.rag.openBtn') }}
        </el-button>
        <el-button
          v-if="ragEnabled && canUpdate && category"
          :icon="Refresh"
          :loading="reindexing"
          @click="confirmReindex"
        >
          {{ t('documents.rag.reindexBtn') }}
        </el-button>
        <el-button
          v-if="canUpload && category"
          :icon="FolderAdd"
          @click="openFolderDialog()"
        >
          {{ t('documents.createFolder') }}
        </el-button>
      </div>
    </div>

    <!-- 拖拽上传区：内联在表格上方（参考上传识别页样式） -->
    <div v-if="canUpload && category" class="upload-area">
      <el-upload
        drag multiple :auto-upload="true"
        :action="uploadUrl"
        :headers="uploadHeaders"
        :before-upload="beforeUpload"
        :on-success="onUploadSuccess"
        :on-error="onUploadError"
        :show-file-list="false"
      >
        <el-icon class="upload-icon"><UploadFilled /></el-icon>
        <div class="upload-title">{{ t('documents.dropHere') }}</div>
        <div class="upload-hint">{{ t('documents.uploadTip') }}</div>
      </el-upload>
    </div>

    <!-- 面包屑：当前路径导航 -->
    <div v-if="category" class="breadcrumb-bar">
      <el-icon class="bc-icon"><Folder /></el-icon>
      <a class="bc-link" @click="openFolder('')">{{ t('documents.breadcrumbRoot') }}</a>
      <template v-for="(seg, i) in breadcrumbs" :key="seg.path">
        <span class="bc-sep">/</span>
        <a class="bc-link" @click="openFolder(seg.path)">{{ seg.name }}</a>
      </template>
    </div>

    <!-- 列表区：plain 1px 边框容器，避开 SaaS-card 套娃 -->
    <div class="docs-table-wrap">
      <el-table
        v-if="folders.length > 0 || rows.length > 0"
        :data="tableData"
        v-loading="loading"
        border
        stripe
        size="small"
      >
        <!-- 名称列：文件夹 / 文件 两种渲染 -->
        <el-table-column :label="t('documents.colName')" min-width="220">
          <template #default="{ row }">
            <template v-if="(row as any).__kind === 'folder'">
              <div class="name-cell folder-cell" @click="openFolder((row as any).path)">
                <el-icon class="folder-icon"><FolderOpened /></el-icon>
                <span class="strong">{{ (row as any).name }}</span>
              </div>
            </template>
            <template v-else>
              <div class="name-cell file-cell">
                <el-icon class="file-icon" :class="kind(row as DocumentItem)">
                  <component :is="iconFor(row as DocumentItem)" />
                </el-icon>
                <span class="strong">{{ (row as DocumentItem).name }}</span>
                <el-tooltip :content="(row as DocumentItem).originalFilename" placement="top" v-if="(row as DocumentItem).originalFilename !== (row as DocumentItem).name">
                  <el-icon class="alias-hint"><InfoFilled /></el-icon>
                </el-tooltip>
              </div>
            </template>
          </template>
        </el-table-column>
        <!-- 文件夹只有名称列；文件列：类型/大小/上传人/上传时间/存储路径 -->
        <el-table-column v-if="rows.length > 0" :label="t('documents.colType')" width="80">
          <template #default="{ row }">
            <template v-if="(row as any).__kind === 'file'">
              <code class="mono">{{ extOf(row as DocumentItem) }}</code>
            </template>
          </template>
        </el-table-column>
        <el-table-column v-if="rows.length > 0" :label="t('documents.colSize')" width="120" align="right">
          <template #default="{ row }">
            <template v-if="(row as any).__kind === 'file'">
              <span class="num">{{ formatBytes((row as DocumentItem).sizeBytes) }}</span>
            </template>
          </template>
        </el-table-column>
        <el-table-column v-if="rows.length > 0" :label="t('documents.colUploader')" prop="uploaderName" width="100">
          <template #default="{ row }">
            <template v-if="(row as any).__kind === 'file'">
              <span class="muted">{{ (row as DocumentItem).uploaderName || '—' }}</span>
            </template>
          </template>
        </el-table-column>
        <el-table-column v-if="rows.length > 0" :label="t('documents.colUploadedAt')" width="200">
          <template #default="{ row }">
            <template v-if="(row as any).__kind === 'file'">
              <span class="mono-time">{{ (row as DocumentItem).createdAt }}</span>
            </template>
          </template>
        </el-table-column>
        <el-table-column v-if="rows.length > 0" :label="t('documents.colStorageKey')" min-width="220">
          <template #default="{ row }">
            <template v-if="(row as any).__kind === 'file'">
              <el-tooltip :content="(row as DocumentItem).storageKey" placement="top">
                <code class="mono key-cell">{{ (row as DocumentItem).storageKey }}</code>
              </el-tooltip>
            </template>
          </template>
        </el-table-column>
        <!-- 操作列：只有文件有；文件夹没操作 -->
        <el-table-column :label="t('common.actions')" width="160" align="center" fixed="right">
          <template #default="{ row }">
            <template v-if="(row as any).__kind === 'file'">
              <!-- 预览 / 下载 / 重命名：图标直显 -->
              <el-tooltip :content="t('documents.preview')" placement="top">
                <el-button size="small" link :icon="View" @click="onCommand('preview', row as DocumentItem)" />
              </el-tooltip>
              <el-tooltip :content="t('documents.download')" placement="top">
                <el-button size="small" link :icon="Download" @click="onCommand('download', row as DocumentItem)" />
              </el-tooltip>
              <el-tooltip v-if="canUpdate" :content="t('documents.rename')" placement="top">
                <el-button size="small" link :icon="Edit" @click="onCommand('rename', row as DocumentItem)" />
              </el-tooltip>
              <!-- 索引 / 删除：保留在 ··· 下拉 -->
              <el-dropdown
                v-if="canUpdate || canDelete"
                trigger="click"
                @command="(cmd: string) => onCommand(cmd, row as DocumentItem)"
              >
                <el-button
                  :icon="MoreFilled"
                  size="small"
                  link
                  :loading="indexingId === (row as DocumentItem).id"
                />
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-if="ragEnabled && canUpdate" command="index">
                      <el-icon><Search /></el-icon>{{ t('documents.rag.indexBtn') }}
                    </el-dropdown-item>
                    <el-dropdown-item v-if="canDelete" command="delete" divided>
                      <span class="danger-text">
                        <el-icon><Delete /></el-icon>{{ t('common.delete') }}
                      </span>
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
            <template v-else>
              <el-tooltip :content="t('documents.folderOpen')" placement="top">
                <el-button
                  size="small" link
                  :icon="ArrowRight"
                  @click="openFolder((row as any).path)"
                />
              </el-tooltip>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <!-- 空状态：当前路径下没有任何文件 / 文件夹 -->
      <div v-else-if="!loading && (noPerm || (category && !canView))" class="empty-state no-perm">
        <el-icon class="empty-icon"><Lock /></el-icon>
        <h2 class="empty-title">{{ t('documents.noPermTitle') }}</h2>
        <p class="empty-desc muted">{{ t('documents.noPermDesc') }}</p>
      </div>
      <div v-else-if="!loading && category" class="empty-state">
        <el-icon class="empty-icon"><FolderOpened /></el-icon>
        <h2 class="empty-title">{{ t('documents.emptyFolder') }}</h2>
        <p class="empty-desc muted">{{ t('documents.emptyDesc') }}</p>
      </div>
    </div>

    <!-- 重命名弹窗 -->
    <el-dialog v-model="renameVisible" :title="t('documents.renameTitle')" width="480px">
      <el-form @submit.prevent>
        <el-form-item :label="t('documents.colName')">
          <el-input
            v-model="renameValue"
            :placeholder="t('documents.renamePlaceholder')"
            maxlength="255"
            show-word-limit
            @keyup.enter="commitRename"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="renameVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="renameSaving" @click="commitRename">
          {{ t('common.save') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 新建文件夹弹窗 -->
    <el-dialog v-model="folderVisible" :title="t('documents.createFolderTitle')" width="560px" :close-on-click-modal="false">
      <el-form ref="folderFormRef" :model="folderForm" :rules="folderRules" @submit.prevent>
        <el-form-item :label="t('documents.folderPath')" prop="path">
          <el-input
            v-model="folderForm.path"
            :placeholder="t('documents.folderPathHint')"
            maxlength="255"
            @keyup.enter="commitCreateFolder"
          />
          <div class="folder-hint muted">{{ t('documents.folderPathHint') }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="folderVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="folderSaving" @click="commitCreateFolder">
          {{ t('documents.createFolder') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 预览 drawer（多格式预览由 ProjectFileViewer 走 @file-viewer/preset-office） -->
    <el-drawer
      v-model="previewVisible"
      class="preview-drawer"
      size="80%"
      direction="rtl"
      :destroy-on-close="true"
      :show-close="false"
    >
      <!-- 自定义 header：左侧文件名 + 右侧下载 / 关闭快捷按钮。
           用 #header slot 替换 Element Plus 默认的「title + ×」布局。
           注意：el-drawer 的 <button v-if="showClose">×</button> 在 slot 之外、
           是 <header> 的兄弟节点 —— 必须 :show-close="false" 才能彻底隐藏默认 ×。 -->
      <template #header>
        <div class="preview-drawer-header">
          <div class="preview-drawer-title" :title="previewTitle">
            <el-icon class="preview-title-icon"><Document /></el-icon>
            <span class="preview-title-text">{{ previewTitle }}</span>
          </div>
          <div class="preview-drawer-actions">
            <el-tooltip :content="t('common.download')" placement="bottom">
              <el-button
                :icon="Download"
                circle
                plain
                size="default"
                @click="onPreviewDownload({ storageKey: previewKey, name: previewTitle })"
              />
            </el-tooltip>
            <el-tooltip :content="t('common.close')" placement="bottom">
              <el-button
                :icon="Close"
                circle
                plain
                size="default"
                @click="previewVisible = false"
              />
            </el-tooltip>
          </div>
        </div>
      </template>
      <ProjectFileViewer
        v-if="previewKey"
        :storage-key="previewKey"
        :filename="previewTitle"
        @download="onPreviewDownload"
      />
    </el-drawer>

    <!-- 档案库问答抽屉：检索范围由后端按 documents:{slug}:view 圈定 -->
    <RagChatDrawer v-model="ragVisible" @open-document="onRagOpenDocument" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import {
  ArrowRight, Close, Delete, Document, Download, Edit, Folder, FolderAdd, FolderOpened, InfoFilled, Lock, MagicStick, MoreFilled, Refresh, Search, UploadFilled, View
} from '@element-plus/icons-vue';
import * as ElIcons from '@element-plus/icons-vue';
import { useI18n } from 'vue-i18n';
import { useAuthStore } from '@/stores/auth';
import { archiveApi, documentApi, fileRawUrl, type ArchiveLibrary, type DocumentItem, type FolderItem, type BrowseResult } from '@/api/document';
import { ragApi } from '@/api/rag';
import ProjectFileViewer from '@/components/ProjectFileViewer.vue';
import RagChatDrawer from '@/components/documents/RagChatDrawer.vue';

const { t } = useI18n();
const auth = useAuthStore();
const route = useRoute();
const router = useRouter();

const loading = ref(false);
const rows = ref<DocumentItem[]>([]);
const folders = ref<FolderItem[]>([]);
const category = ref<ArchiveLibrary | null>(null);
 const noPerm = ref(false);            // 当前 slug 档案库存在但用户无 view perm
 const currentPath = ref('');          // 档案库内当前路径（空 = 根）

 /** 档案库级 4 权限 —— 由后端 FileController 同步校验（per-archive view 是 preview/download 的硬要求） */
const canView   = computed(() => !!category.value && auth.hasPerm(`documents:${category.value.slug}:view`));
const canUpload = computed(() => !!category.value && auth.hasPerm(`documents:${category.value.slug}:upload`));
const canUpdate = computed(() => !!category.value && auth.hasPerm(`documents:${category.value.slug}:update`));
const canDelete = computed(() => !!category.value && auth.hasPerm(`documents:${category.value.slug}:delete`));

const renameVisible = ref(false);
const renameValue = ref('');
const renameTarget = ref<DocumentItem | null>(null);
const renameSaving = ref(false);

const folderVisible = ref(false);
const folderForm = reactive<{ path: string }>({ path: '' });
const folderSaving = ref(false);
const folderFormRef = ref<FormInstance>();
// Unicode 友好：字母/数字/连字符/下划线/空格，支持中文；段间用 / 分隔
const FOLDER_PATH_RE = /^[\p{L}\p{N}\-_\s]+(?:\/[\p{L}\p{N}\-_\s]+)*$/u;
const folderRules: FormRules = {
  path: [
    { required: true, message: t('common.required'), trigger: 'blur' },
    {
      validator: (_r, v: string, cb) => {
        if (!v || !FOLDER_PATH_RE.test(v.trim())) {
          cb(new Error(t('documents.folderPathInvalid')));
        } else cb();
      },
      trigger: 'blur',
    },
  ],
};

const previewVisible = ref(false);
const previewKey = ref('');     // 当前预览文件的 storageKey（替代原 previewUrl）
const previewTitle = ref('');

const uploadUrl = computed(() => {
  const cid = category.value?.id ?? '';
  const qs = new URLSearchParams({ categoryId: String(cid) });
  if (currentPath.value) qs.set('path', currentPath.value);
  return `/api/documents/upload?${qs.toString()}`;
});
const uploadHeaders = computed(() => ({
  Authorization: `Bearer ${localStorage.getItem('accessToken') || ''}`,
}));

/** 面包屑：把 currentPath 拆成段，每段可点击跳转 */
const breadcrumbs = computed(() => {
  const segs = currentPath.value.split('/').filter(Boolean);
  const out: { name: string; path: string }[] = [];
  let acc = '';
  for (const s of segs) {
    acc = acc ? `${acc}/${s}` : s;
    out.push({ name: s, path: acc });
  }
  return out;
});

/** 表格数据 = 文件夹 + 文件（用 __kind 区分，文件夹排前） */
const tableData = computed(() => {
  const folderRows = folders.value.map((f) => ({
    __kind: 'folder' as const,
    name: f.name,
    path: f.path,
  }));
  const fileRows = rows.value.map((r) => ({
    ...r,
    __kind: 'file' as const,
  }));
  return [...folderRows, ...fileRows];
});

function openFolder(path: string) {
  router.push({ name: route.name!, params: { slug: route.params.slug }, query: path ? { path } : {} });
}

function resolveIcon(name?: string) {
  if (!name) return ElIcons.Folder;
  return (ElIcons as Record<string, unknown>)[name] || ElIcons.Folder;
}

function extOf(row: DocumentItem): string {
  const name = row.originalFilename || row.name || '';
  const i = name.lastIndexOf('.');
  return i < 0 ? '—' : name.substring(i + 1).toLowerCase();
}

function kind(row: DocumentItem): string {
  const ext = extOf(row);
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp'].includes(ext)) return 'image';
  if (ext === 'pdf') return 'pdf';
  if (['xls', 'xlsx', 'csv'].includes(ext)) return 'sheet';
  if (['doc', 'docx'].includes(ext)) return 'doc';
  return 'file';
}

function iconFor(row: DocumentItem) {
  switch (kind(row)) {
    case 'image': return ElIcons.Picture;
    case 'pdf':   return ElIcons.Document;
    case 'sheet': return ElIcons.Grid;
    case 'doc':   return ElIcons.EditPen;
    default:      return ElIcons.Files;
  }
}

function formatBytes(n: number): string {
  if (!n) return '0 B';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  let v = n;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(v >= 100 || i === 0 ? 0 : 1)} ${u[i]}`;
}

// ---------- load ----------
async function loadBySlug(slug: string) {
  if (!slug) return;
  loading.value = true;
  try {
    // archiveApi.list() 已被后端按 documents:{slug}:view 过滤 —— 不在列表里 = 无 view perm 或不存在
    const all = await archiveApi.list();
    const cat = all.find((c) => c.slug === slug);
    if (!cat) {
      category.value = null;
      noPerm.value = true;
      rows.value = [];
      folders.value = [];
      return;
    }
    category.value = cat;
    noPerm.value = false;
    // 读取 URL query 中的 path（每次进入/点击文件夹都重新 load）
    currentPath.value = ((route.query.path as string) ?? '').trim();
    await reloadDocs();
  } catch {
    // 401/403 时拦截器已处理；这里只 reset 状态
    category.value = null;
    noPerm.value = true;
    rows.value = [];
    folders.value = [];
  } finally {
    loading.value = false;
  }
}

async function reloadDocs() {
  if (!category.value) return;
  const data: BrowseResult = await documentApi.browse(category.value.id, currentPath.value);
  folders.value = data.folders;
  rows.value = data.files;
}

/** 监听 URL query.path 变化（双击文件夹跳转后） */
watch(() => route.query.path, (v) => {
  const next = ((v as string) ?? '').trim();
  if (next !== currentPath.value) {
    currentPath.value = next;
    reloadDocs();
  }
});

// ---------- actions ----------
function openFolderDialog() {
  if (!category.value) return;
  folderForm.path = '';
  folderVisible.value = true;
  // 下一个 tick 聚焦 input
  setTimeout(() => folderFormRef.value?.clearValidate(), 0);
}

async function commitCreateFolder() {
  if (!category.value || !folderFormRef.value) return;
  const valid = await folderFormRef.value.validate().catch(() => false);
  if (!valid) return;
  folderSaving.value = true;
  try {
    const res = await documentApi.createFolder(
      category.value.id,
      folderForm.path.trim(),
      currentPath.value,
    );
    ElMessage.success(t('documents.folderCreated', { path: res.path }));
    folderVisible.value = false;
    // 刷新当前目录的浏览结果（新文件夹会立刻出现在列表）
    await reloadDocs();
  } catch (e: any) {
    const msg = e?.message || String(e);
    ElMessage.error(t('documents.folderCreateFail', { msg }));
  } finally {
    folderSaving.value = false;
  }
}

function beforeUpload(file: File): boolean {
  if (file.size > 100 * 1024 * 1024) {
    ElMessage.error(t('documents.uploadFail', { msg: '> 100MB' }));
    return false;
  }
  return true;
}

function onUploadSuccess(resp: any, file: { name: string }) {
  // el-upload 的 :on-success 只看 HTTP 2xx；后端 GlobalExceptionHandler 即使出错也返回 200 + {code: 500}，
  // 必须显式检查 resp.code === 0，否则会"假成功"。
  if (resp?.code !== 0) {
    const msg = resp?.message || 'Unknown error';
    ElMessage.error(t('documents.uploadFail', { msg }));
    return;
  }
  ElMessage.success(`${t('documents.uploadSuccess')} · ${file.name}`);
  reloadDocs();
}

function onUploadError(err: any) {
  // axios-el 上传错误：err.message 通常是后端 message
  const msg = err?.message || String(err);
  ElMessage.error(t('documents.uploadFail', { msg }));
}

async function onCommand(cmd: string, row: DocumentItem) {
  switch (cmd) {
    case 'preview':  return preview(row);
    case 'download': return download(row);
    case 'rename':   return openRename(row);
    case 'index':    return indexOne(row);
    case 'delete':   return confirmDelete(row);
  }
}

// ---------- RAG 档案库问答 ----------
const ragVisible = ref(false);
/** null = 探测中；false = 后端 RAG 未启用（@ConditionalOnProperty 整组不注册，探测必失败） */
const ragEnabled = ref<boolean | null>(null);
const indexingId = ref<number | null>(null);
const reindexing = ref(false);

/** 探测一次即可：RAG 开关是启动期配置，不会在运行中变化 */
async function probeRag() {
  if (ragEnabled.value !== null) return;
  try {
    await ragApi.stats();
    ragEnabled.value = true;
  } catch {
    ragEnabled.value = false;
  }
}

/** 单档案建立索引。覆盖式写入，重复点击安全。 */
async function indexOne(row: DocumentItem) {
  if (indexingId.value != null) return;
  indexingId.value = row.id;
  try {
    const r = await ragApi.indexDocument(row.id);
    if (r.indexed) {
      ElMessage.success(t('documents.rag.indexDone', {
        name: r.filename || row.name, n: r.chunkCount,
      }));
    } else {
      ElMessage.warning(t('documents.rag.indexSkipped', {
        name: r.filename || row.name, note: r.note || '—',
      }));
    }
  } catch (e: any) {
    ElMessage.error(t('documents.rag.indexFailed', { msg: e?.message || String(e) }));
  } finally {
    indexingId.value = null;
  }
}

/** 整库重建。会真实调用 embedding 接口，必须先确认。 */
async function confirmReindex() {
  if (!category.value || reindexing.value) return;
  try {
    await ElMessageBox.confirm(
      t('documents.rag.reindexConfirm', { name: category.value.name }),
      t('documents.rag.reindexBtn'),
      {
        type: 'warning',
        confirmButtonText: t('common.ok'),
        cancelButtonText: t('common.cancel'),
      },
    );
  } catch {
    return;
  }
  reindexing.value = true;
  const loading = ElMessageBox.alert(t('documents.rag.reindexing'), '', {
    type: 'info',
    showClose: false,
    closeOnClickModal: false,
    showCancelButton: false,
  }).catch(() => undefined);
  try {
    const r = await ragApi.reindexLibrary(category.value.id);
    ElMessage.success(t('documents.rag.reindexDone', {
      indexed: r.indexed, skipped: r.skipped, failed: r.failed, total: r.total,
    }));
  } catch (e: any) {
    ElMessage.error(t('documents.rag.reindexFailed', { msg: e?.message || String(e) }));
  } finally {
    loading.then(() => { /* 关闭 loading 提示 */ });
    reindexing.value = false;
  }
}

/** 点击引用 → 拉档案详情 → 复用已有预览抽屉（引用可能来自其它文件夹，故不能只查当前 rows） */
async function onRagOpenDocument(documentId: number) {
  try {
    const d = await documentApi.detail(documentId);
    preview(d);
  } catch (e: any) {
    ElMessage.error(t('documents.rag.sourceLoadFailed', { msg: e?.message || String(e) }));
  }
}

function preview(row: DocumentItem) {
  previewKey.value = row.storageKey;
  previewTitle.value = row.name;
  previewVisible.value = true;
}

/** ProjectFileViewer 内"下载原文件"按钮：复用同一 download() 逻辑 */
function onPreviewDownload(row: { storageKey: string; name: string }) {
  download({ storageKey: row.storageKey, originalFilename: row.name, name: row.name } as DocumentItem);
}

async function download(row: DocumentItem) {
  // 用 fetch() 直接拿 blob（不走 axios —— 拦截器对 blob 响应处理有 bug，
  // 之前用 axios + blob 时下载的文件损坏）。
  //
  // 关键：JWT cache 问题。用户的 JWT 可能在授权变更前签发，里面没新加的 perm。
  // 第一轮 fetch 若 403/401 → 自动 refresh JWT → 重试一次。这覆盖了
  // 「admin 给了权限但用户的旧 JWT 还没刷」的场景 —— 不需要用户重新登录。
  try {
    let blob = await fetchFileBlob(row.storageKey);
    if (!blob) {
      // 第一轮失败 → refresh JWT → 重试
      await auth.refreshTokens();
      blob = await fetchFileBlob(row.storageKey);
    }
    if (!blob) throw new Error('refresh+retry failed');
    saveBlob(blob, row.originalFilename || row.name);
  } catch (err: any) {
    ElMessage.error(err?.message || '下载失败');
  }
}

async function fetchFileBlob(key: string): Promise<Blob | null> {
  const resp = await fetch(fileRawUrl(key, true), {
    headers: { Authorization: `Bearer ${localStorage.getItem('accessToken') || ''}` },
  });
  if (resp.ok) return resp.blob();
  if (resp.status === 403 || resp.status === 401) return null;  // 触发 refresh + retry
  // 其他错误（如 500 / 404）—— 提取后端 message 给用户
  const body = await resp.json().catch(() => ({}));
  throw new Error(body?.message || `下载失败: HTTP ${resp.status}`);
}

function saveBlob(blob: Blob, filename: string) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(a.href);
}

function openRename(row: DocumentItem) {
  renameTarget.value = row;
  renameValue.value = row.name;
  renameVisible.value = true;
}

async function commitRename() {
  if (!renameTarget.value) return;
  const v = renameValue.value.trim();
  if (!v) return;
  renameSaving.value = true;
  try {
    await documentApi.rename(renameTarget.value.id, v);
    ElMessage.success(t('documents.renameSuccess'));
    renameVisible.value = false;
    await reloadDocs();
  } catch {
    /* interceptor toasted */
  } finally {
    renameSaving.value = false;
  }
}

async function confirmDelete(row: DocumentItem) {
  try {
    await ElMessageBox.confirm(
      t('documents.deleteConfirm', { name: row.name }),
      t('documents.delete'),
      {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel'),
        confirmButtonClass: 'el-button--danger',
        customClass: 'doc-delete-confirm',
      },
    );
  } catch {
    return;
  }
  try {
    await documentApi.remove(row.id);
    ElMessage.success(t('documents.deleteSuccess'));
    await reloadDocs();
    // 顺便刷新档案库 fileCount
    if (category.value) {
      const all = await archiveApi.list();
      const fresh = all.find((c) => c.id === category.value!.id);
      if (fresh) category.value = fresh;
    }
  } catch {
    /* interceptor toasted */
  }
}

// ---------- lifecycle ----------
const slug = computed(() => route.params.slug as string);
watch(slug, (v) => loadBySlug(v), { immediate: false });
onMounted(() => {
  loadBySlug(slug.value);
  probeRag();
});
</script>

<style scoped>
.header-actions {
  display: flex;
  gap: 8px;
}
.title-icon {
  margin-right: 6px;
  color: var(--color-primary);
  vertical-align: -2px;
}
.name-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.file-icon {
  font-size: 16px;
  color: var(--color-text-secondary);
}
.file-icon.image { color: #67c23a; }
.file-icon.pdf   { color: #f56c6c; }
.file-icon.sheet { color: #409eff; }
.file-icon.doc   { color: #e6a23c; }
.alias-hint {
  font-size: 12px;
  color: var(--color-text-muted);
}
.strong {
  font-weight: 500;
  color: var(--color-text);
}
.mono {
  font-family: 'SF Mono', Menlo, monospace;
  font-size: 12px;
  color: var(--color-text-secondary);
}
.muted {
  color: var(--color-text-muted);
  font-size: 12px;
}
.num {
  font-variant-numeric: tabular-nums;
}
.key-cell {
  max-width: 360px;
  overflow: hidden;
  text-overflow: ellipsis;
  display: inline-block;
  vertical-align: middle;
  white-space: nowrap;
}
.danger-text {
  color: var(--color-danger);
}
:deep(.doc-delete-confirm .el-message-box__btns .el-button--danger) {
  background: var(--color-danger);
  border-color: var(--color-danger);
}

/* ===== 页面布局（参照数据源/档案库页，§7.2） ===== */
.docs-page {
  /* 页面至少填满 el-main（视口 - 64px 顶栏）：
   * - 表格只有 2 行时，不会留下大片空白在页面底部
   * - 弹窗居中时落在实际页面内容上，不会"飘"在视口空白处 */
  min-height: calc(100vh - 64px);
  display: flex;
  flex-direction: column;
}
.docs-page .page-header {
  display: flex; justify-content: space-between; align-items: center;
  /* 吸顶：标题 + 操作按钮始终可见 */
  position: sticky;
  top: 0;
  z-index: 30;
  background: var(--color-bg);
  /* 紧凑 padding：让页头总高度 = 标题高度 + 小呼吸 */
  padding: 8px 0;
  margin-bottom: 0;
  flex-shrink: 0;
}
.docs-page .page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; line-height: 1.3; }
.docs-page .page-desc  { margin: 4px 0 0; font-size: 13px; line-height: 1.4; }
.docs-table-wrap {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-elev);
  /* overflow: visible 让 th sticky 透到外层页面滚动容器 */
  overflow: visible;
  /* 与吸顶页头之间不留多余间距（紧贴） */
  margin-top: 0;
  /* 让表格 wrap 也参与 flex 分配（剩余空间吃掉） */
  flex: 1;
  display: flex;
  flex-direction: column;
}
.docs-table-wrap > .el-table,
.docs-table-wrap > .el-table > .el-table__inner-wrapper,
.docs-table-wrap > .el-table > .el-table__inner-wrapper > .el-table__body-wrapper {
  flex: 1;
}

/* 表头不吸顶 —— <el-table> 的内部布局对 position:sticky 极敏感，
 * 把 header-wrapper 设 sticky 会让 tbody 跑到表头上方（实测 bug）。
 * 退而求其次：只让页头吸顶；表头随页面滚动自然消失/出现。 */
:deep(.docs-table-wrap .el-table__header-wrapper) {
  background: var(--color-bg);
}
:deep(.docs-table-wrap .el-table__header-wrapper table) {
  background: var(--color-bg);
}

/* ===== 拖拽上传区（参考上传识别页 invoice/Upload.vue） ===== */
.upload-area {
  margin-bottom: 8px;
}
:deep(.upload-area .el-upload) { width: 100%; display: block; }
:deep(.upload-area .el-upload-dragger) {
  background: linear-gradient(135deg, var(--color-primary-softer) 0%, var(--color-bg-elev) 100%);
  border: 2px dashed var(--color-border-strong);
  border-radius: 14px;
  padding: 8px 12px;
  transition: all 0.2s ease;
}
:deep(.upload-area .el-upload-dragger:hover) {
  border-color: var(--color-primary);
  background: linear-gradient(135deg, #eff6ff 0%, #eef2ff 100%);
}
.upload-icon { font-size: 32px; color: var(--color-primary); margin-bottom: 4px; }
.upload-title { font-size: 14px; font-weight: 600; color: var(--color-text); margin-bottom: 2px; }
.upload-hint { font-size: 12px; color: var(--color-text-secondary); }

/* 新建文件夹表单提示 */
.folder-hint {
  margin-top: 4px;
  font-size: 12px;
}

/* ===== 面包屑 ===== */
.breadcrumb-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--color-text-secondary);
}
.bc-icon { color: var(--color-primary); font-size: 16px; }
.bc-link {
  cursor: pointer;
  color: var(--color-text-secondary);
  padding: 2px 4px;
  border-radius: 3px;
  transition: background 0.12s, color 0.12s;
}
.bc-link:hover {
  background: var(--color-primary-soft);
  color: var(--color-primary);
}
.bc-sep { color: var(--color-text-muted); user-select: none; }

/* ===== 文件夹行 ===== */
.folder-cell { cursor: pointer; user-select: none; }
.folder-cell:hover .folder-icon { color: var(--color-primary); }
.folder-icon {
  font-size: 16px;
  color: #e6a23c; /* Element 警示色，比蓝色更适合"文件夹"语义 */
}

/* 空状态：表格容器随行数自动调节（不强制 min-height）。
 * 空状态卡片本身给一个小最小高度（视觉舒展），但不让容器永远占满视口。 */
.docs-table-wrap .empty-state {
  min-height: 320px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 30px 12px;
  gap: 12px;
}
.empty-state .empty-icon {
  font-size: 56px;
  color: var(--color-primary-soft);
  background: var(--color-bg-elev);
  border: 1px solid var(--color-border-soft);
  border-radius: 50%;
  width: 96px;
  height: 96px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 8px;
}
.empty-state .empty-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text);
  margin: 0;
}
.empty-state .empty-desc {
  font-size: 13px;
  max-width: 460px;
  margin: 0 0 6px;
  line-height: 1.55;
}

/*
 * 预览抽屉自定义 header
 *  - 左侧：文件名 + Document 图标
 *  - 右侧：下载 / 关闭 圆形快捷按钮
 *  - 与 Element Plus 默认 header 同样的水平 padding（容器已自带），
 *    这里只管 flex 布局和省略号截断
 */
.preview-drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-width: 0;
  flex: 1 1 auto;
}
.preview-drawer-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
  color: var(--color-text);
}
.preview-title-icon {
  flex-shrink: 0;
  font-size: 16px;
  color: var(--color-primary);
}
.preview-title-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 15px;
  font-weight: 600;
}
.preview-drawer-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
</style>

<style>
/*
 * 关键修复（v1.5.2）：StylePanel.vue:207 的全局规则
 *   .el-drawer__header { display: none; }
 * 把所有 el-drawer 的 <header> 都隐藏了 —— 包括我们的预览抽屉，
 * 所以 #header slot 写进去的内容看不到。
 *
 * 用更高的特异性（0,2,0 vs 0,1,0）只对本抽屉恢复显示。
 * 非 scoped 块：el-drawer 经 <Teleport> 到 body，scoped :deep() 匹配不到。
 */
.preview-drawer .el-drawer__header {
  display: flex;
}
</style>
