<script setup lang="ts">
/**
 * 笔记书写面 —— Typora 式纸面：标题就是正文第一行，下面直接是所见即所得的 Markdown。
 *
 * 相对旧版（Monaco 源码 ‖ 预览 双栏 + 独立工具条）的改动：
 *  - 去掉 H1/H2/B/I/🔗/≡… 那排工具条。格式用 Markdown 标记本身表达，
 *    工具条在即时渲染下是冗余的 —— 这正是 Typora 去掉它的原因。
 *  - 标题从带边框的 el-input 改成无边框大字输入，聚焦时才显一条极淡下边线。
 *  - 自动保存、乐观锁 version、冲突三条与之前完全一致，没动。
 *
 * 本组件是**内容的唯一权威来源**；往父级抛的 content-change / stats 仅供
 * 大纲与状态栏展示，父级不回写正文。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { PriceTag } from '@element-plus/icons-vue';
import { noteApi, type NoteItem, type NoteScope } from '@/api/note';
import { insertBlock, toggleLinePrefix, wrapSelection, type FormatAction, type SlashCommand } from '@/utils/markdownCommands';
import NoteWriter from './NoteWriter.vue';

export type SaveStatus = 'saved' | 'saving' | 'dirty' | 'error';
export interface WriterStats {
  chars: number;
  words: number;
  lines: number;
  heading: string;
}

const props = defineProps<{ note: NoteItem }>();
const emit = defineEmits<{
  (e: 'saved', note: NoteItem): void;
  /** 新建模式首次保存成功：带后端返回的实体（含 id）抛给父级，由它 replace 路由 */
  (e: 'created', note: NoteItem): void;
  (e: 'conflict'): void;
  (e: 'dirty', dirty: boolean): void;
  (e: 'cancel'): void;
  /** 正文实时副本 —— 父级拿它算编辑态大纲，**不要**拿它当数据源 */
  (e: 'content-change', content: string): void;
  (e: 'ui-state', s: { status: SaveStatus; errorText: string }): void;
  (e: 'stats', stats: WriterStats): void;
}>();

const { t } = useI18n();

/**
 * 新建模式：note.id 为 0 —— 还没有持久化实体，所以**不做自动保存**
 * （自动保存要 PUT /notes/{id}，此时没有 id 可 PUT）。
 * 用户点「保存」时才 POST 创建，随后父级 replace 到 /documents/notes/{newId}，
 * 组件自动切到编辑模式并接管自动保存。
 */
const isNew = computed(() => !props.note.id);

const title = ref(props.note.title);
const content = ref(props.note.content ?? '');
const tags = ref<string[]>([...(props.note.tags ?? [])]);
const scopeType = ref<NoteScope>(props.note.scopeType);
const scopeDeptId = ref<number | undefined>(props.note.scopeDeptId);
const categoryId = ref<number | undefined>(props.note.categoryId);

const saving = ref(false);
const dirty = ref(false);
const errorText = ref('');

const writerRef = ref<InstanceType<typeof NoteWriter> | null>(null);

let version = props.note.version ?? 0;
let timer: ReturnType<typeof setTimeout> | null = null;
/** 冲突后停止自动保存，直到用户明确选择「覆盖保存」或「查看最新」。 */
let conflicted = false;
/** 打开时锁一次，避免把首次同步 content 误判成用户输入。 */
let priming = true;

// ---- 状态上报（底部状态栏） ----

function pushState(status: SaveStatus) {
  emit('ui-state', { status, errorText: errorText.value });
}

function onContent(v: string) {
  content.value = v;
  emit('content-change', v);
}

function onStats(s: WriterStats) {
  emit('stats', s);
}

// ---- 初始化 ----

// 首次挂载要主动回填一次：父级的大纲/统计靠 content-change 起步，
// 而下面那个 watch 只在 note.id 变化时触发，挂载时不会自己跑。
//
// priming 也必须在这里解锁 —— 上面那个 watch 没有 immediate，
// 漏了这行会让 priming 永远停在 true，watch([title, content]) 里的
// `if (priming) return` 恒中，dirty 永远 false，自动保存与手动保存全部失效。
onMounted(() => {
  emit('content-change', content.value);
  pushState('saved');
  void nextTick(() => { priming = false; });
});

watch(
  () => props.note.id,
  () => {
    title.value = props.note.title;
    content.value = props.note.content ?? '';
    tags.value = [...(props.note.tags ?? [])];
    scopeType.value = props.note.scopeType;
    scopeDeptId.value = props.note.scopeDeptId;
    categoryId.value = props.note.categoryId;
    version = props.note.version ?? 0;
    dirty.value = false;
    errorText.value = '';
    conflicted = false;
    priming = true;
    emit('content-change', content.value);
    pushState('saved');
    void nextTick(() => { priming = false; });
  },
);

/**
 * 可见性（scopeType / categoryId）由共享弹窗改，它会更新父级的 note 并回流到 props。
 * 这里必须跟着同步，否则下一次自动保存会把刚改好的可见性用旧值覆盖回去。
 * 组件内没有这两个字段的编辑入口，所以不存在「用户在改、我在覆盖」的冲突。
 */
watch(
  () => [props.note.scopeType, props.note.scopeDeptId, props.note.categoryId],
  () => {
    scopeType.value = props.note.scopeType;
    scopeDeptId.value = props.note.scopeDeptId;
    categoryId.value = props.note.categoryId;
  },
);

// ---- 自动保存 ----

watch([title, content], () => {
  if (priming) return;
  markDirty();
  // 新建模式不自动保存：还没有 id，PUT 无处可发。靠「保存」按钮走 POST。
  if (isNew.value) return;
  if (conflicted) return;
  if (timer) clearTimeout(timer);
  timer = setTimeout(() => { void save(false); }, 2000);
});

function markDirty() {
  if (!dirty.value) {
    dirty.value = true;
    emit('dirty', true);
    pushState('dirty');
  }
}

/** immediate=true 表示用户主动点保存 / Ctrl+S，跳过 debounce。 */
async function save(immediate = false) {
  if (timer) { clearTimeout(timer); timer = null; }
  if (!dirty.value || saving.value) return;
  if (!title.value.trim()) {
    errorText.value = t('note.titleRequired');
    pushState('error');
    return;
  }
  saving.value = true;
  errorText.value = '';
  pushState('saving');
  try {
    // 新建：先落库拿到 id，父级 replace 路由后本组件自动转入编辑模式
    if (isNew.value) {
      const created = await noteApi.create({
        title: title.value.trim(),
        content: content.value,
        scopeType: scopeType.value,
        scopeDeptId: scopeType.value === 'TEAM' ? scopeDeptId.value : undefined,
        categoryId: scopeType.value === 'LIBRARY' ? categoryId.value : undefined,
        tags: tags.value,
        relatedDocumentId: props.note.relatedDocumentId,
      });
      dirty.value = false;
      emit('created', created);
      emit('dirty', false);
      pushState('saved');
      if (immediate) ElMessage.success(t('note.saved'));
      return;
    }

    const res = await noteApi.update(props.note.id, {
      title: title.value.trim(),
      content: content.value,
      scopeType: scopeType.value,
      scopeDeptId: scopeType.value === 'TEAM' ? scopeDeptId.value : undefined,
      categoryId: scopeType.value === 'LIBRARY' ? categoryId.value : undefined,
      tags: tags.value,
      relatedDocumentId: props.note.relatedDocumentId,
      version,
    });
    version = res.version ?? version + 1;
    dirty.value = false;
    conflicted = false;
    emit('saved', res);
    emit('dirty', false);
    pushState('saved');
    if (immediate) ElMessage.success(t('note.saved'));
  } catch (err: unknown) {
    const status = (err as { response?: { status?: number } })?.response?.status;
    if (status === 409) {
      conflicted = true;
      emit('conflict');
      pushState('error');
    } else {
      errorText.value = (err as Error)?.message || t('note.loadFailed');
      pushState('error');
    }
  } finally {
    saving.value = false;
  }
}

/** 冲突态下用户选择「覆盖保存」：跳过 conflicted 拦截再提交一次。 */
async function forceSave() {
  conflicted = false;
  dirty.value = true;
  await save(true);
}

function giveUp() {
  conflicted = false;
  dirty.value = false;
  pushState('saved');
  emit('cancel');
}

function onSaveShortcut() {
  void save(true);
}

// ---- 快捷输入：命令表 ----

const TABLE_TEMPLATE = [
  '| 列 A | 列 B |',
  '| --- | --- |',
  '|  |  |',
].join('\n');

/**
 * 斜杠命令表。snippet 是纯 Markdown 片段（与界面语言无关），
 * title 走 i18n —— 切语言时 NoteWriter 会用新的表重新 configure 编辑器。
 * cursorOffset 把光标放进成对标记中间，省掉用户再挪一次光标。
 */
const slashCommands = computed<SlashCommand[]>(() => [
  { id: 'h1', title: t('note.cmd.h1'), snippet: '# ' },
  { id: 'h2', title: t('note.cmd.h2'), snippet: '## ' },
  { id: 'h3', title: t('note.cmd.h3'), snippet: '### ' },
  { id: 'bold', title: t('note.cmd.bold'), snippet: '****', cursorOffset: 2 },
  { id: 'italic', title: t('note.cmd.italic'), snippet: '**', cursorOffset: 1 },
  { id: 'strike', title: t('note.cmd.strike'), snippet: '~~~~', cursorOffset: 2 },
  { id: 'code', title: t('note.cmd.code'), snippet: '``', cursorOffset: 1 },
  { id: 'codeblock', title: t('note.cmd.codeblock'), snippet: '```\n\n```', cursorOffset: 4 },
  { id: 'link', title: t('note.cmd.link'), snippet: '[](https://)', cursorOffset: 1 },
  { id: 'quote', title: t('note.cmd.quote'), snippet: '> ' },
  { id: 'ul', title: t('note.cmd.ul'), snippet: '- ' },
  { id: 'ol', title: t('note.cmd.ol'), snippet: '1. ' },
  { id: 'task', title: t('note.cmd.task'), snippet: '- [ ] ' },
  { id: 'table', title: t('note.cmd.table'), snippet: TABLE_TEMPLATE, cursorOffset: 3 },
  { id: 'hr', title: t('note.cmd.hr'), snippet: '---' },
]);

/** 选中文字时浮现在选区上方的一排；label 是短字形，tip 走 i18n。 */
const formatActions = computed<FormatAction[]>(() => [
  { id: 'h1', label: 'H1', tip: t('note.cmd.h1'), run: (v) => toggleLinePrefix(v, '# ', t('note.ph.title')) },
  { id: 'h2', label: 'H2', tip: t('note.cmd.h2'), run: (v) => toggleLinePrefix(v, '## ', t('note.ph.title')) },
  { id: 'h3', label: 'H3', tip: t('note.cmd.h3'), run: (v) => toggleLinePrefix(v, '### ', t('note.ph.title')) },
  { id: 'bold', label: 'B', tip: t('note.cmd.bold'), run: (v) => wrapSelection(v, '**', '**') },
  { id: 'italic', label: 'I', tip: t('note.cmd.italic'), run: (v) => wrapSelection(v, '*', '*') },
  { id: 'strike', label: 'S', tip: t('note.cmd.strike'), run: (v) => wrapSelection(v, '~~', '~~') },
  { id: 'code', label: '</>', tip: t('note.cmd.code'), run: (v) => wrapSelection(v, '`', '`') },
  { id: 'link', label: '🔗', tip: t('note.cmd.link'), run: (v) => wrapSelection(v, '[', '](https://)') },
  { id: 'quote', label: '❝', tip: t('note.cmd.quote'), run: (v) => toggleLinePrefix(v, '> ', t('note.ph.quote')) },
  { id: 'ul', label: '≡', tip: t('note.cmd.ul'), run: (v) => toggleLinePrefix(v, '- ', t('note.ph.listItem')) },
  { id: 'ol', label: '1.', tip: t('note.cmd.ol'), run: (v) => toggleLinePrefix(v, (n) => `${n}. `, t('note.ph.listItem')) },
  { id: 'task', label: '☑', tip: t('note.cmd.task'), run: (v) => toggleLinePrefix(v, '- [ ] ', t('note.ph.listItem')) },
  { id: 'table', label: '▦', tip: t('note.cmd.table'), run: (v) => insertBlock(v, TABLE_TEMPLATE, 3) },
]);

defineExpose({
  forceSave,
  giveUp,
  save,
  scrollToLine: (line: number) => writerRef.value?.scrollToLine(line),
  focusTitle: () => (document.getElementById('note-paper-title') as HTMLInputElement | null)?.focus(),
});

onBeforeUnmount(() => {
  if (timer) clearTimeout(timer);
});
</script>

<template>
  <div class="note-paper">
    <div class="paper-inner">
      <input
        id="note-paper-title"
        v-model="title"
        class="paper-title"
        maxlength="255"
        :placeholder="t('note.titlePlaceholder')"
      />

      <div class="paper-meta">
        <el-icon class="meta-icon"><PriceTag /></el-icon>
        <el-select
          v-model="tags"
          multiple
          filterable
          allow-create
          default-first-option
          :multiple-limit="10"
          class="tags-select"
          :placeholder="t('note.tagsPlaceholder')"
        />
      </div>

      <p v-if="errorText" class="paper-error">{{ errorText }}</p>

      <NoteWriter
        ref="writerRef"
        :model-value="content"
        :placeholder="t('note.editorPlaceholder')"
        :save-shortcut="onSaveShortcut"
        :slash-commands="slashCommands"
        :format-actions="formatActions"
        @update:model-value="onContent"
        @stats="onStats"
      />
    </div>
  </div>
</template>

<style scoped>
/* 纸面不滚动 —— 滚动权交给编辑器内部的 .cm-scroller，
 * 否则页面会同时出现「纸面滚动条 + 编辑器滚动条」两根竖条。 */
.note-paper {
  height: 100%;
  min-height: 0;
  overflow: hidden;
  /* 外层＝桌面灰底，纸面本身是白的，两者要能一眼分开 */
  background: var(--color-bg);
}
/* 纸张：限宽居中，两侧留白 —— Typora / MarkText 的核心观感。
 * 宽度走全局变量，与只读态 NoteViewer 共用同一个值。
 * 白底 + 左右细线 + 投影，让「纸」从灰底里浮出来；
 * 上下不画边，顶栏与状态栏的 border 已经提供了横向分隔。 */
.paper-inner {
  max-width: var(--note-paper-width);
  margin: 0 auto;
  padding: 40px 40px;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--color-bg-elev);
  border-left: 1px solid var(--color-border-soft);
  border-right: 1px solid var(--color-border-soft);
  box-shadow: var(--note-paper-shadow);
}

/* 标题就是正文第一行：无边框，聚焦才显一条极淡下边线 */
.paper-title {
  flex-shrink: 0;
  width: 100%;
  border: none;
  border-bottom: 1px solid transparent;
  background: transparent;
  outline: none;
  padding: 0 0 6px;
  margin: 0 0 10px;
  font-size: 30px;
  font-weight: 700;
  line-height: 1.35;
  letter-spacing: -0.01em;
  color: var(--color-text);
  font-family: inherit;
  transition: border-color 0.18s ease;
}
.paper-title::placeholder { color: var(--color-text-muted); font-weight: 700; }
.paper-title:focus { border-bottom-color: var(--color-border); }

.paper-meta {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 18px;
}
.meta-icon {
  color: var(--color-text-muted);
  font-size: 14px;
  flex: 0 0 auto;
}
.tags-select { flex: 1; min-width: 0; }
.tags-select :deep(.el-select__wrapper) {
  box-shadow: none;
  background: transparent;
  padding-left: 0;
}
.tags-select :deep(.el-select__wrapper:hover) { box-shadow: none; }

.paper-error {
  flex-shrink: 0;
  margin: 0 0 14px;
  font-size: 13px;
  color: var(--color-danger);
}
</style>