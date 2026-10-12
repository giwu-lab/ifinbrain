<script setup lang="ts">
/**
 * Markdown 书写器 —— CodeMirror 6 + utils/markdownLivePreview.ts。
 *
 * 为什么是 CM6 而不是 Monaco / tiptap：
 *  - Monaco 是源码编辑器，做「即时渲染」要自己盖一层装饰，且撞上 AGENTS §10.1 的 worker 绕法；
 *  - tiptap 是真正的所见即所得，但必须写 markdown ↔ 富文档的往返序列化，
 *    笔记正文是长期存储的数据，复杂表格与嵌套列表来回一次就可能变形。
 * CM6 这条路里**真源永远是 markdown 文本**，live preview 只是视觉装饰层，没有序列化环节。
 *
 * 主题不重建：CM6 注入的是普通 CSS，所以直接写 var(--color-*)，
 * 暗色模式由 CSS 变量自己切换，不用像 Monaco 那样手动换主题名。
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Compartment, EditorState } from '@codemirror/state';
import { EditorView, drawSelection, keymap, placeholder as cmPlaceholder } from '@codemirror/view';
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import { markdownKeymap, markdownLanguage } from '@codemirror/lang-markdown';
import type { KeyBinding } from '@codemirror/view';
import { livePreview } from '@/utils/markdownLivePreview';
import {
  continueListItem,
  continueTableRow,
  formatToolbar,
  slashCommandPanel,
  wrapSelection,
  type FormatAction,
  type SlashCommand,
} from '@/utils/markdownCommands';

const props = withDefaults(defineProps<{
  modelValue: string;
  placeholder?: string;
  /** Ctrl+S 的落点，由父组件（持自动保存逻辑）接管 */
  saveShortcut?: (() => void) | null;
  /** 斜杠命令表；父组件负责填 i18n 文案 */
  slashCommands?: SlashCommand[];
  /** 选区浮动工具条的动作表 */
  formatActions?: FormatAction[];
}>(), { modelValue: '', placeholder: '', saveShortcut: null, slashCommands: () => [], formatActions: () => [] });

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void;
  /** 光标位置变化时上报，给底部状态栏显示「当前章节」 */
  (e: 'stats', stats: { chars: number; words: number; lines: number; heading: string }): void;
}>();

const host = ref<HTMLElement | null>(null);
let view: EditorView | null = null;

/** Typora 的计数口径：中日韩字符逐字算，英文按单词算。 */
function countWords(text: string): number {
  const cjk = text.match(/[\u3400-\u9fff\uf900-\ufaff]/g)?.length ?? 0;
  const latin = text
    .replace(/[\u3400-\u9fff\uf900-\ufaff]/g, ' ')
    .match(/[A-Za-z0-9]+(?:['’-][A-Za-z0-9]+)*/g)?.length ?? 0;
  return cjk + latin;
}

function emitStats(state: EditorState) {
  const doc = state.doc;
  const text = doc.toString();
  const head = state.selection.main.head;
  // 从光标所在行往上找最近的标题：只在「距离上一个标题」的范围里扫，
  // 不从文首开始，否则每次移动光标都要全量遍历一遍长文档。
  const headLine = doc.lineAt(head).number;
  let heading = '';
  for (let i = headLine; i >= 1; i--) {
    const m = /^#{1,6}\s+(.+?)\s*#*\s*$/.exec(doc.line(i).text);
    if (m) {
      heading = m[1].trim();
      break;
    }
  }
  emit('stats', { chars: text.length, words: countWords(text), lines: doc.lines, heading });
}

/** 格式快捷键：成对标记用空占位，光标自动落中间。 */
function formatShortcuts(): KeyBinding[] {
  return [
    { key: 'Mod-b', run: (v) => wrapSelection(v, '**', '**') },
    { key: 'Mod-i', run: (v) => wrapSelection(v, '*', '*') },
    { key: 'Mod-Shift-x', run: (v) => wrapSelection(v, '~~', '~~') },
    { key: 'Mod-`', run: (v) => wrapSelection(v, '`', '`') },
    { key: 'Mod-k', run: (v) => wrapSelection(v, '[', '](https://)') },
    // 回车续列表 / 补表格分隔行；都不命中就交回 defaultKeymap
    { key: 'Enter', run: (v) => continueListItem(v) || continueTableRow(v) },
  ];
}

/**
 * 快捷输入装在独立 Compartment 里 —— 命令表由父组件按 i18n 构造，
 * 切语言时重新 configure 即可，不必销毁重建整个编辑器（那会丢光标与撤销栈）。
 */
const cmdCompartment = new Compartment();
function commandExtensions() {
  const cmds = props.slashCommands;
  const actions = props.formatActions;
  return [
    slashCommandPanel(() => cmds),
    formatToolbar(() => actions),
    keymap.of(formatShortcuts()),
  ];
}

onMounted(() => {
  if (!host.value) return;
  const state = EditorState.create({
    doc: props.modelValue ?? '',
    extensions: [
      history(),
      drawSelection(),
      // Compartment 必须排在 defaultKeymap 前面：斜杠面板要抢 Enter/Tab/方向键
      cmdCompartment.of(commandExtensions()),
      keymap.of([
        { key: 'Mod-s', run: () => { props.saveShortcut?.(); return true; } },
        ...defaultKeymap,
        ...historyKeymap,
      ]),
      // 必须用 markdownLanguage（CommonMark + GFM），**不能用 markdown()**：
      // markdown() 返回的是不含 GFM 的另一个 Language 实例，表格 / 删除线 /
      // 任务清单 / 自动链接都不会被识别，live preview 的对应分支就成了死代码。
      // Language 本身可直接作为 extension 传入（等价于 LanguageSupport.of）。
      markdownLanguage,
      keymap.of(markdownKeymap),
      livePreview(),
      EditorView.lineWrapping,
      EditorView.contentAttributes.of({ 'aria-label': 'markdown-editor' }),
      // placeholder 的回调要返回 HTMLElement，不能直接返回字符串
      cmPlaceholder(() => {
        const span = document.createElement('span');
        span.textContent = props.placeholder;
        return span;
      }),
      EditorView.theme({
        '&': {
          height: '100%',
          color: 'var(--color-text)',
          backgroundColor: 'transparent',
          fontSize: '15px',
        },
        '.cm-scroller': {
          fontFamily: 'inherit',
          lineHeight: '1.8',
          overflow: 'auto',
        },
        '.cm-content': {
          caretColor: 'var(--color-primary)',
          // 底部留白要克制。之前按 Typora 的写法给了 40vh，窗口一矮就
          // 「内容高 + 40vh」超过容器，编辑器永远挂着一根不该出现的滚动条。
          padding: '0 0 24px 0',
        },
        '&.cm-focused': { outline: 'none' },
        '.cm-line': { padding: '0' },
        '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--color-primary)', borderLeftWidth: '2px' },
        '.cm-selectionBackground, ::selection': { backgroundColor: 'var(--color-primary-soft)' },
        '&.cm-focused .cm-selectionBackground': { backgroundColor: 'var(--color-primary-soft)' },
        '.cm-placeholder': { color: 'var(--color-text-muted)' },
      }),
      EditorView.updateListener.of((u) => {
        if (u.docChanged) emit('update:modelValue', u.state.doc.toString());
        if (u.docChanged || u.selectionSet) emitStats(u.state);
      }),
    ],
  });
  view = new EditorView({ state, parent: host.value });
  emitStats(state);
});

// 外部换笔记 / 初次加载时同步内容；内容一致则不动，避免打断光标
watch(
  () => props.modelValue,
  (v) => {
    if (!view) return;
    const cur = view.state.doc.toString();
    if (cur === (v ?? '')) return;
    view.dispatch({ changes: { from: 0, to: cur.length, insert: v ?? '' } });
  },
);

watch(
  () => [props.slashCommands, props.formatActions],
  () => view?.dispatch({ effects: cmdCompartment.reconfigure(commandExtensions()) }),
);

onBeforeUnmount(() => {
  view?.destroy();
  view = null;
});

defineExpose({
  focus: () => view?.focus(),
  /** 大纲点击跳转：按 1-based 源码行号滚过去（行号由 extractSourceOutline 给出） */
  scrollToLine: (line: number) => {
    if (!view) return;
    const n = Math.min(Math.max(Math.trunc(line), 1), view.state.doc.lines);
    const pos = view.state.doc.line(n).from;
    view.dispatch({ effects: EditorView.scrollIntoView(pos, { y: 'start' }) });
    view.focus();
  },
});
</script>

<template>
  <div ref="host" class="note-writer" />
</template>

<style scoped>
/* 纸面内的书写区：占满剩余高度，滚动**只交给 .cm-scroller**。
 * 若这里再开一层 overflow，页面就会同时出现「纸面滚动条 + 编辑器滚动条」两根。 */
.note-writer {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

/* CM6 的 DOM 是命令式创建的，拿不到 scoped 属性名，必须 :deep。
 * 颜色一律走 token，暗色模式自动跟随（AGENTS §7.3.6）。 */
.note-writer :deep(.md-lp-strong) { font-weight: 700; color: var(--color-text); }
.note-writer :deep(.md-lp-em) { font-style: italic; }
.note-writer :deep(.md-lp-strike) { text-decoration: line-through; color: var(--color-text-muted); }

.note-writer :deep(.md-lp-h1) {
  font-size: 28px;
  font-weight: 700;
  line-height: 1.4;
  margin-top: 24px;
  color: var(--color-text);
}
.note-writer :deep(.md-lp-h2) {
  font-size: 22px;
  font-weight: 700;
  line-height: 1.45;
  margin-top: 20px;
  color: var(--color-text);
}
.note-writer :deep(.md-lp-h3) {
  font-size: 18px;
  font-weight: 700;
  margin-top: 16px;
  color: var(--color-text);
}
.note-writer :deep(.md-lp-h4),
.note-writer :deep(.md-lp-h5),
.note-writer :deep(.md-lp-h6) {
  font-size: 16px;
  font-weight: 700;
  margin-top: 12px;
  color: var(--color-text-secondary);
}

.note-writer :deep(.md-lp-code) {
  font-family: var(--font-mono);
  font-size: 13px;
  background: var(--color-primary-softer);
  color: var(--color-primary);
  padding: 1px 5px;
  border-radius: var(--radius-xs);
}

.note-writer :deep(.md-lp-link) {
  color: var(--color-primary);
  text-decoration: underline;
  text-decoration-color: var(--color-primary-soft);
  text-underline-offset: 3px;
  cursor: pointer;
}

.note-writer :deep(.md-lp-quote) {
  border-left: 2px solid var(--color-border-strong);
  color: var(--color-text-secondary);
  font-style: italic;
}

.note-writer :deep(.md-lp-bullet) {
  color: var(--color-text-muted);
  padding-right: 4px;
}
.note-writer :deep(.md-lp-punct) { color: var(--color-text-muted); }

/* 任务清单方框 */
.note-writer :deep(.md-lp-check) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  margin-right: 6px;
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-xs);
  background: var(--color-bg-elev);
  color: transparent;
  font-size: 11px;
  line-height: 1;
  cursor: pointer;
  vertical-align: -1px;
  user-select: none;
}
.note-writer :deep(.md-lp-check.is-checked) {
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: #fff;
}

.note-writer :deep(hr.md-lp-hr) {
  border: none;
  border-top: 1px solid var(--color-border);
  margin: 24px 0;
}

/* ===== 代码块折叠态 =====
 * 底色不能用 var(--color-bg)：纸面本身就是这个色，等于没画。
 * 用主色与页面底色的混合，深浅主题下都只比正文略深一点，不会喧宾夺主。 */
.note-writer :deep(.md-lp-pre) {
  position: relative;
  margin: 12px 0;
  padding: 12px 14px;
  border: 1px solid var(--color-border);
  border-left: 3px solid var(--color-primary);
  border-radius: var(--radius-sm);
  background: var(--color-bg);
  background: color-mix(in srgb, var(--color-primary) 8%, var(--color-bg));
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.6;
  color: var(--color-text);
  white-space: pre-wrap;
  word-break: break-word;
  cursor: text;
}
.note-writer :deep(.md-lp-pre code) {
  background: transparent;
  padding: 0;
  font-family: inherit;
  font-size: inherit;
}
.note-writer :deep(.md-lp-lang) {
  position: absolute;
  top: 6px;
  right: 10px;
  font-size: 11px;
  color: var(--color-text-muted);
  font-family: inherit;
}
/* 展开态：源码可见但仍保持代码块观感（每行一个 span，左边框连成一条竖线） */
.note-writer :deep(.md-lp-codeblock) {
  border-left: 3px solid var(--color-primary);
  padding-left: 12px;
  color: var(--color-text-secondary);
}

/* ===== 表格折叠态 ===== */
.note-writer :deep(.md-lp-table) {
  border-collapse: collapse;
  width: 100%;
  margin: 12px 0;
  font-size: 13px;
  table-layout: auto;
}
.note-writer :deep(.md-lp-table td),
.note-writer :deep(.md-lp-table th) {
  border: 1px solid var(--color-border);
  padding: 6px 10px;
  text-align: left;
  vertical-align: top;
}
.note-writer :deep(.md-lp-table th) {
  background: var(--color-bg);
  font-weight: 600;
}
.note-writer :deep(.md-lp-table-src) {
  border-left: 3px solid var(--color-primary);
  padding-left: 12px;
  color: var(--color-text-secondary);
}

/* ===== 斜杠命令面板 / 选区浮动工具条 ===== */
.note-writer :deep(.cm-float-panel) {
  position: absolute;
  z-index: 40;
  pointer-events: auto;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-popover);
  box-shadow: 0 6px 18px rgba(15, 23, 42, 0.12);
}

.note-writer :deep(.cm-slash-menu) {
  min-width: 180px;
  max-height: 260px;
  overflow-y: auto;
  padding: 4px;
}
.note-writer :deep(.cm-slash-item) {
  font-size: 13px;
  color: var(--color-text-secondary);
  padding: 6px 10px;
  border-radius: var(--radius-xs);
  cursor: pointer;
  white-space: nowrap;
}
.note-writer :deep(.cm-slash-item.is-active) {
  background: var(--color-primary-soft);
  color: var(--color-primary);
  font-weight: 600;
}

.note-writer :deep(.cm-fmt-bar) {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 3px;
}
.note-writer :deep(.cm-fmt-btn) {
  border: none;
  background: transparent;
  cursor: pointer;
  padding: 3px 8px;
  min-width: 26px;
  font-size: 12px;
  line-height: 18px;
  border-radius: var(--radius-xs);
  /* 字形只有 12px，灰字细体几乎看不出是按钮 —— 加粗 + 提到主文本色才立得住 */
  font-weight: 700;
  color: var(--color-text);
  font-family: var(--font-mono);
}
.note-writer :deep(.cm-fmt-btn:hover) {
  background: var(--color-primary-soft);
  color: var(--color-primary);
}
</style>