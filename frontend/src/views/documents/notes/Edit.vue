<script setup lang="ts">
/**
 * 笔记编辑页 —— 独立路由，new 与 :id 共用一个组件（同 data-mgmt/query/Detail 的做法）。
 *
 *   /documents/notes/new          新建（不自动保存，点「保存」才 POST）
 *   /documents/notes/:id(\d+)     编辑（输入停 2s 自动保存，version 乐观锁）
 *
 * 布局参照 MarkText / Typora：通栏顶栏 + 左侧大纲 + 居中纸面 + 底部状态栏。
 * 正文编辑全部在 NoteEditor 里 —— 编辑器要占满宽度，
 * 和列表挤在一屏会让书写区被压到没法用。
 */
import { computed, nextTick, onActivated, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowLeft, Share, Clock, Upload, Download, Expand, Fold } from '@element-plus/icons-vue';
import { noteApi, type NoteItem, type NoteScope } from '@/api/note';
import type { ArchiveLibrary } from '@/api/document';
import { extractOutline, extractSourceOutline, renderMarkdown } from '@/utils/markdown';
import { useAuthStore } from '@/stores/auth';
import NoteEditor, { type SaveStatus, type WriterStats } from './components/NoteEditor.vue';
import NoteViewer from './components/NoteViewer.vue';
import NoteOutline, { type OutlineNode } from './components/NoteOutline.vue';
import NoteStatusBar from './components/NoteStatusBar.vue';
import ShareDialog from './components/ShareDialog.vue';
import RevisionDrawer from './components/RevisionDrawer.vue';

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const note = ref<NoteItem | null>(null);
const loading = ref(false);
const dirty = ref(false);
const conflicted = ref(false);
const editorRef = ref<InstanceType<typeof NoteEditor> | null>(null);

const shareVisible = ref(false);
const historyVisible = ref(false);
const publishVisible = ref(false);
const publishTarget = ref<number | undefined>(undefined);
const libraries = ref<ArchiveLibrary[]>([]);

/** 大纲侧栏开合（状态存本地即可，不值得进 store） */
const outlineOpen = ref(true);

/**
 * 可用高度实测。
 *
 * <p><b>为什么不能写死 calc(100vh - 64px)</b>
 * `64px` 只是 SidebarLayout 的顶栏高度，而真正决定可用高度的是 `el-main` ——
 * 它由 flex 算出，且里面还塞着 TabBar（多标签页，仅在有 tab 时渲染）。
 * 写死会让页面比可用高度高出整整一个 TabBar，el-main 直接冒出滚动条。
 * 四种布局的顶栏高度也各不相同，写死必然在某些布局下溢出。
 *
 * 实测口径：el-main 高度 − 同一容器里除本页外的其他元素高度（即 TabBar）。
 */
const rootRef = ref<HTMLElement | null>(null);
const availH = ref<number | null>(null);

function measure() {
  const el = rootRef.value;
  const inner = el?.parentElement as HTMLElement | null;
  const main = el?.closest('.el-main') as HTMLElement | null;
  if (!el || !inner || !main) return;
  let siblings = 0;
  for (const s of Array.from(inner.children)) {
    if (s !== el) siblings += (s as HTMLElement).offsetHeight;
  }
  availH.value = Math.max(240, main.clientHeight - siblings);
}

let measureTimer: ReturnType<typeof setTimeout> | null = null;
function scheduleMeasure() {
  if (measureTimer) clearTimeout(measureTimer);
  measureTimer = setTimeout(measure, 50);
}

onMounted(() => {
  void nextTick(() => {
    measure();
    window.addEventListener('resize', scheduleMeasure);
  });
});

// keep-alive 下切走再切回、TabBar 数量变化，都要重新量
onActivated(() => void nextTick(measure));

onBeforeUnmount(() => {
  if (measureTimer) clearTimeout(measureTimer);
  window.removeEventListener('resize', scheduleMeasure);
});
/**
 * 正文实时副本 —— **仅供大纲与统计展示**。
 * 数据的唯一权威来源在 NoteEditor 里，父级不回写正文，否则两份状态会打架。
 */
const liveContent = ref('');
const stats = ref<WriterStats | null>(null);
const saveState = ref<SaveStatus>('saved');
const saveError = ref('');

/** /documents/notes/new → null；/documents/notes/12 → 12 */
const noteId = computed<number | null>(() => {
  const raw = route.params.id;
  const n = raw == null ? NaN : Number(raw);
  return Number.isFinite(n) && n > 0 ? n : null;
});
const isNew = computed(() => note.value?.id === 0);

const canEdit = computed(() => isNew.value || (!!note.value?.canEdit && auth.hasPerm('note:update')));
const canShare = computed(() => !isNew.value && !!note.value && auth.hasPerm('note:share'));

/**
 * 大纲：编辑态取**源码行号**（渲染结果没有行号概念），只读态取渲染后的 DOM 锚点。
 * 两套来源由 utils/markdown.ts 保证口径一致（都只认 ATX 标题）。
 */
const outline = computed<OutlineNode[]>(() => {
  const md = canEdit.value ? liveContent.value : (note.value?.content ?? '');
  if (!md) return [];
  if (canEdit.value) {
    return extractSourceOutline(md).map((i) => ({ level: i.level, text: i.text, line: i.line }));
  }
  return extractOutline(renderMarkdown(md)).map((i) => ({ level: i.level, text: i.text, id: i.id }));
});

function onJump(item: OutlineNode) {
  if (canEdit.value) {
    if (item.line) editorRef.value?.scrollToLine(item.line);
  } else if (item.id) {
    document.getElementById(item.id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}

function onContentChange(v: string) {
  liveContent.value = v;
}

function onStats(s: WriterStats) {
  stats.value = s;
}

function onUiState(s: { status: SaveStatus; errorText: string }) {
  saveState.value = s.status;
  saveError.value = s.errorText;
}

/**
 * 新建草稿。带 categoryId 查询参数进来时预设为「挂该档案库」——
 * 档案库文件列表页的「笔记」抽屉就是走这条入口。
 */
function draft(): NoteItem {
  const catId = route.query.categoryId ? Number(route.query.categoryId) : undefined;
  return {
    id: 0,
    title: '',
    content: '',
    excerpt: '',
    scopeType: (catId ? 'LIBRARY' : 'PERSONAL') as NoteScope,
    categoryId: catId,
    tags: [],
    pinned: false,
    status: 1,
    /** 草稿尚无归属人；真实 id / owner 会在首次保存后由后端返回覆盖 */
    ownerId: 0,
    version: 0,
    canEdit: true,
    canShare: false,
    sharedWithMe: false,
    owner: true,
  };
}

async function load() {
  const id = noteId.value;
  loading.value = true;
  conflicted.value = false;
  dirty.value = false;
  saveState.value = 'saved';
  saveError.value = '';
  try {
    note.value = id ? await noteApi.detail(id) : draft();
    // 只读态也要有大纲；编辑态由 NoteEditor 挂载后回填 liveContent
    if (!id) liveContent.value = note.value.content ?? '';
  } catch {
    // 后端对「不存在或无权」统一返 404，这里不做二次提示（http 拦截器已弹）
    note.value = null;
  } finally {
    loading.value = false;
  }
}

onMounted(async () => {
  noteApi.libraries().then((l) => { libraries.value = l ?? []; }).catch(() => { libraries.value = []; });
  await load();
});

// 同一路由在 new ↔ :id 之间切换时重新装载
watch(noteId, load);

function back() {
  void router.push('/documents/notes');
}

/** 新建首次保存成功：把 URL 换成真实 id，组件随即切到编辑模式（自动保存接管） */
async function onCreated(created: NoteItem) {
  if (!created.id) return;
  await router.replace(`/documents/notes/${created.id}`);
  await load();
}

function onSaved(updated: NoteItem) {
  if (!note.value) return;
  // 共享弹窗改的是可见性，正文以 NoteEditor 内的为准
  note.value = { ...updated, content: note.value.content };
  dirty.value = false;
}

function onConflict() {
  conflicted.value = true;
}

/** 用户在冲突条上选「放弃我的修改」：脏标记要跟着清掉，否则离开时还会弹确认 */
function onCancel() {
  dirty.value = false;
}

async function loadLatest() {
  if (!note.value) return;
  note.value = await noteApi.detail(note.value.id);
  conflicted.value = false;
  liveContent.value = note.value.content ?? '';
  ElMessage.success(t('note.saved'));
}

async function openPublish() {
  if (!note.value) return;
  if (!note.value.categoryId) {
    ElMessage.warning(t('note.publishNoLibrary'));
    shareVisible.value = true;
    return;
  }
  publishTarget.value = note.value.categoryId;
  publishVisible.value = true;
}

async function doPublish() {
  if (!note.value || !publishTarget.value) return;
  await noteApi.publish(note.value.id, { categoryId: publishTarget.value });
  ElMessage.success(t('note.publishOk'));
  publishVisible.value = false;
}

/** 导出本地 .md：不落库、不留痕迹，与「发布到档案库」是两件事 */
function exportMd() {
  if (!note.value) return;
  const blob = new Blob([`# ${note.value.title}\n\n${note.value.content ?? ''}`],
    { type: 'text/markdown;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${(note.value.title || 'note').replace(/[\\/:*?"<>|]/g, '_')}.md`;
  a.click();
  URL.revokeObjectURL(url);
}

onBeforeRouteLeave(async () => {
  if (!dirty.value) return true;
  try {
    await ElMessageBox.confirm(t('note.unsavedOnLeave'), t('common.confirmTitle'), { type: 'warning' });
    return true;
  } catch { return false; }
});
</script>

<template>
  <div ref="rootRef" class="note-edit-page fade-in-up" v-loading="loading"
    :style="availH ? { height: availH + 'px' } : undefined">
    <!-- 顶栏：编辑器页用专用顶栏而非 h1 大标题（同 design/form/Designer） -->
    <header class="note-topbar">
      <div class="topbar-left">
        <el-button link :icon="ArrowLeft" @click="back">{{ t('note.backToList') }}</el-button>
        <span class="banner" />
        <span class="note-title">{{ isNew ? t('note.createTitle') : (note?.title || t('note.title')) }}</span>
        <span v-if="dirty" class="dirty-mark" :title="t('note.dirty')">●</span>
        <span v-if="note && !isNew" class="note-meta">
          <span>{{ note.ownerName }}</span>
          <span class="mono-time">v{{ note.version }}</span>
          <span v-if="!note.canEdit" class="readonly-mark">{{ t('note.readOnly') }}</span>
        </span>
        <span v-else-if="isNew" class="note-meta" :title="t('note.mdHint')">{{ t('note.mdHint') }}</span>
      </div>
      <div class="topbar-right">
        <el-tooltip :content="t('note.toggleOutline')" placement="bottom">
          <el-button size="small" :icon="outlineOpen ? Fold : Expand" @click="outlineOpen = !outlineOpen" />
        </el-tooltip>
        <template v-if="note && canShare">
          <el-divider direction="vertical" />
          <el-tooltip :content="t('note.share')" placement="bottom">
            <el-button size="small" :icon="Share" @click="shareVisible = true" />
          </el-tooltip>
          <el-tooltip :content="t('note.history')" placement="bottom">
            <el-button size="small" :icon="Clock" @click="historyVisible = true" />
          </el-tooltip>
          <el-tooltip :content="t('note.publish')" placement="bottom">
            <el-button size="small" :icon="Upload" @click="openPublish" />
          </el-tooltip>
          <el-tooltip :content="t('note.download')" placement="bottom">
            <el-button size="small" :icon="Download" @click="exportMd" />
          </el-tooltip>
          <el-divider direction="vertical" />
        </template>
        <el-button
          v-if="note && canEdit"
          type="primary"
          @click="editorRef?.save(true)"
        >{{ t('common.save') }}</el-button>
      </div>
    </header>

    <div class="note-main">
      <div v-if="conflicted" class="conflict-bar">
        <div class="conflict-text">
          <strong>{{ t('note.conflict', { name: note?.lastEditorName || '—' }) }}</strong>
          <span class="muted">{{ t('note.conflictTip') }}</span>
        </div>
        <div class="conflict-ops">
          <el-button size="small" @click="loadLatest">{{ t('note.reloadLatest') }}</el-button>
          <el-button size="small" type="danger" plain @click="editorRef?.forceSave()">{{ t('note.overwrite') }}</el-button>
          <el-button size="small" @click="editorRef?.giveUp()">{{ t('note.discard') }}</el-button>
        </div>
      </div>

      <div class="note-workbench">
        <!-- 大纲与纸面作为一组居中：大纲紧贴纸面左边，中间不再留一大片死灰。
             has-outline 只在真的有大纲时才把组宽加上大纲那一栏。 -->
        <div class="workbench-inner" :class="{ 'has-outline': outlineOpen && outline.length > 0 }">
          <aside v-if="outlineOpen && outline.length" class="workbench-side">
            <NoteOutline :items="outline" @jump="onJump" />
          </aside>

          <div class="workbench-main">
            <NoteEditor
              v-if="note && canEdit"
              ref="editorRef"
              :note="note"
              @saved="onSaved"
              @created="onCreated"
              @conflict="onConflict"
              @dirty="(d) => { dirty = d; }"
              @cancel="onCancel"
              @content-change="onContentChange"
              @ui-state="onUiState"
              @stats="onStats"
            />
            <div v-else-if="note" class="read-pane">
              <NoteViewer :content="note.content" :show-outline="false" paper />
            </div>
          </div>
        </div>
      </div>
    </div>

    <NoteStatusBar :stats="stats" :status="saveState" :error-text="saveError" />

    <ShareDialog v-if="note && !isNew" v-model="shareVisible" :note="note" @updated="onSaved" />
    <RevisionDrawer v-model="historyVisible" :note="note" @restored="onSaved" />

    <el-dialog v-model="publishVisible" :title="t('note.publishTitle')" width="480px">
      <p class="publish-tip">{{ t('note.publishTip') }}</p>
      <el-form label-width="100px" label-position="left">
        <el-form-item :label="t('note.publishTo')">
          <el-select v-model="publishTarget" class="full">
            <el-option v-for="l in libraries" :key="l.id" :label="l.name" :value="l.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="publishVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :disabled="!publishTarget" @click="doPublish">{{ t('common.ok') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 根容器：编辑器要占满可视区，顶栏与状态栏通栏（同 design/form/Designer） */
.note-edit-page {
  display: flex;
  flex-direction: column;
  /* 高度由 JS 实测写入 inline style（见 measure()）。这里保留一个初值，
   * 仅用于 JS 就绪前的第一帧；写死 100vh - 64px 会在有 TabBar 时溢出。 */
  height: calc(100vh - 64px);
  /* 不用 min-height 兜底：容器一被最小高度撑开，el-main 之外就会多出页面滚动条。
   * flex 子项统一 min-height: 0 才能正常收缩。 */
  min-height: 0;
}

/* 顶栏：返回 + banner + 标题 + 元信息 … 右侧操作。56px 单行，不套 h1 大标题。 */
.note-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 56px;
  padding: 5px 8px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-bg-elev);
  flex-shrink: 0;
}
.topbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.topbar-right {
  display: flex;
  align-items: center;
  gap: 6px;
  justify-content: flex-end;
  flex-shrink: 0;
}
.banner {
  display: inline-block;
  width: 4px;
  height: 18px;
  background: var(--color-primary);
  border-radius: 2px;
  flex-shrink: 0;
}
.note-title {
  font-weight: 600;
  font-size: 15px;
  color: var(--color-text);
  max-width: 36vw;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dirty-mark {
  color: var(--color-warning);
  font-size: 16px;
  flex-shrink: 0;
}
.note-meta {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  font-size: 12px;
  color: var(--color-text-secondary);
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
}
.readonly-mark { color: var(--color-warning); }

/* 主体：冲突条 + 工作台，flex 吃掉剩余高度（不靠魔法数 calc(100vh - 210px)） */
.note-main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--color-bg);
}
.conflict-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 14px;
  margin: 10px 12px 0;
  border: 1px solid var(--color-warning);
  background: var(--color-warning-soft);
  border-radius: var(--radius-sm);
  flex-shrink: 0;
}
.conflict-text { display: flex; align-items: baseline; gap: 10px; font-size: 13px; min-width: 0; }
.conflict-ops { display: flex; gap: 8px; flex: 0 0 auto; }

/* 工作台：大纲 + 纸面
 * 关键改动：两者合成一组（.workbench-inner）整体居中，大纲栏紧贴纸面左边。
 * 之前是「大纲 240px 固定在最左 + 纸面在剩余空间里居中」，两者中间隔着
 * 一大片和背景同色的灰，大纲自己又跟背景同色，整片左侧看起来是空的。 */
.note-workbench {
  flex: 1;
  min-height: 0;
  display: flex;
  justify-content: center;
}
.workbench-inner {
  display: flex;
  flex: 1;
  min-width: 0;
  min-height: 0;
  max-width: var(--note-paper-width);
}
.workbench-inner.has-outline {
  max-width: calc(var(--note-paper-width) + var(--note-outline-width));
}
/* 大纲栏：抬到 --color-bg-elev，与灰色桌面拉开底色差；右边框再叠上纸面自己的
 * 左边框，两条线合成一条明显的分割线，把导航和正文清楚地分开。 */
.workbench-side {
  flex: 0 0 var(--note-outline-width);
  border-right: 1px solid var(--color-border);
  background: var(--color-bg-elev);
  overflow: hidden;
}
/* 视口收窄时先让大纲让位给正文，而不是把纸面挤扁。
 * 组宽要跟着变：≤1280px 是 200 + 820；≤1024px 大纲整个隐藏，组宽回到纸面本身，
 * 否则 calc(100% + 200px) 会让 workbench-inner 比容器还宽。 */
@media (max-width: 1280px) {
  .note-edit-page {
    --note-outline-width: 200px;
    --note-paper-width: 820px;
  }
}
@media (max-width: 1024px) {
  .note-edit-page {
    --note-paper-width: 100%;
  }
  .workbench-side {
    display: none;
  }
  .workbench-inner,
  .workbench-inner.has-outline {
    max-width: 100%;
  }
}
.workbench-main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
/* 只读态：外层灰底 + 滚动，纸面（NoteViewer.is-paper 的 .md-body）负责白底与留白，
 * 所以这里横向不再加 padding，否则纸两侧的灰边会宽得不对称。*/
.read-pane {
  height: 100%;
  overflow-y: auto;
  padding: 20px 0;
  background: var(--color-bg);
}
.publish-tip {
  font-size: 12px;
  color: var(--color-text-muted);
  line-height: 1.7;
  margin: 0 0 12px;
}
.full { width: 100%; }
</style>