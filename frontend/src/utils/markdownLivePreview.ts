/**
 * Markdown 即时渲染（live preview）—— Typora / Obsidian 同款做法。
 *
 * <p><b>为什么不引所见即所得引擎（tiptap / ProseMirror）</b>
 * 笔记正文是长期存储的用户数据。那条路必须写 markdown ↔ 富文本文档的**往返序列化**
 * （marked → HTML → doc，保存时再 getHTML → turndown → markdown），
 * 复杂表格和嵌套列表来回一趟就会变形，等于每次保存都在赌数据保真。
 *
 * <p><b>这里的做法</b>
 * 真源永远是 markdown 文本，渲染只是叠在上面的 Decoration 视觉层。
 * 编辑器里存的、发给后端的、和标题大纲同源的都是同一份文本 ——
 * 没有序列化环节，所以不存在格式丢失的可能。
 *
 * <p><b>折叠 / 展开</b>
 * 行内标记（`#`、`**`、`-`…）是「光标不进去就藏起来」，代码块和表格反过来：
 * 默认折叠成成品（渲染后的代码块 / 真正的 table），光标进入才展开回源码 ——
 * Typora 就是这个行为：读文档时看成品，改的时候才碰标记。
 * 点一下块体把光标送进去即可切换。
 *
 * <p><b>光标安全</b>：标记符号用 replace 装饰藏起来，光标一旦落进该构造范围
 * 就把它还原成源码（Typora 的行为），否则会出现"光标停在看不见的 `**` 里、
 * 退格删掉看不见的字符"这类问题。判定含端点，避免边界处误删。
 */
import { Decoration, EditorView, WidgetType } from '@codemirror/view';
import { RangeSetBuilder, StateField } from '@codemirror/state';
import { ensureSyntaxTree, syntaxTree } from '@codemirror/language';
import { parseTable } from './markdownTable';
import type { DecorationSet } from '@codemirror/view';
import type { EditorState, Extension, Text } from '@codemirror/state';

// ---------------------------------------------------------------- widgets

/** 无序列表圆点：`- ` 换成真正的圆点，而不是留着连字符。 */
class BulletWidget extends WidgetType {
  eq(other: BulletWidget) {
    return other instanceof BulletWidget;
  }
  toDOM() {
    const span = document.createElement('span');
    span.className = 'md-lp-bullet';
    span.textContent = '•';
    return span;
  }
}

/** 任务清单方框：点击直接改写底层的 `[ ]` / `[x]`，而不是画一个假的。 */
class CheckboxWidget extends WidgetType {
  constructor(
    private readonly checked: boolean,
    private readonly pos: number,
  ) {
    super();
  }
  eq(other: CheckboxWidget) {
    return other.checked === this.checked;
  }
  toDOM(view: EditorView) {
    const box = document.createElement('span');
    box.className = this.checked ? 'md-lp-check is-checked' : 'md-lp-check';
    box.textContent = this.checked ? '✓' : '';
    box.addEventListener('mousedown', (e) => {
      e.preventDefault();
      view.dispatch({
        changes: { from: this.pos, to: this.pos + 3, insert: this.checked ? '[ ]' : '[x]' },
      });
    });
    return box;
  }
  ignoreEvent() {
    // 自己处理 mousedown；返回 false 让 CM 继续把点击派发进编辑器
    return false;
  }
}

/** 水平线：把 `---` 换成真正的 hr，视觉上不再是一串连字符。 */
class HrWidget extends WidgetType {
  eq() {
    return true;
  }
  toDOM() {
    const hr = document.createElement('hr');
    hr.className = 'md-lp-hr';
    return hr;
  }
}

/**
 * 代码块折叠态：渲染成带底色的等宽块。
 *
 * 与标题/强调相反，代码块默认**折叠**、光标进入才展开源码 ——
 * Typora 就是这个行为：读文档时看到成品，改代码时才碰标记。
 * 点击块体把光标送进第一行内容，随即 sync 检测到光标在块内，翻回源码。
 */
class CodeBlockWidget extends WidgetType {
  constructor(
    private readonly code: string,
    private readonly info: string,
    private readonly enterAt: number,
  ) {
    super();
  }
  eq(other: CodeBlockWidget) {
    return other.code === this.code && other.info === this.info;
  }
  toDOM(view: EditorView) {
    const wrap = document.createElement('pre');
    wrap.className = 'md-lp-pre';
    const code = document.createElement('code');
    code.textContent = this.code;
    wrap.appendChild(code);
    if (this.info) {
      const tag = document.createElement('span');
      tag.className = 'md-lp-lang';
      tag.textContent = this.info;
      wrap.appendChild(tag);
    }
    wrap.addEventListener('mousedown', (e) => {
      e.preventDefault();
      view.dispatch({ selection: { anchor: this.enterAt } });
      view.focus();
    });
    return wrap;
  }
  ignoreEvent() {
    return false;
  }
}

/** 表格解析已抽到 utils/markdownTable.ts —— 渲染与「回车补分隔行」必须同口径。 */

/** 表格折叠态：渲染成真正的 table，列数按最长行补齐。 */
class TableWidget extends WidgetType {
  constructor(
    private readonly source: string,
    private readonly enterAt: number,
  ) {
    super();
  }
  eq(other: TableWidget) {
    return other.source === this.source;
  }
  toDOM(view: EditorView) {
    const { rows, hasHeader } = parseTable(this.source);
    const table = document.createElement('table');
    table.className = 'md-lp-table';
    const cols = Math.max(1, ...rows.map((r) => r.length));
    rows.forEach((cells, ri) => {
      const tr = document.createElement('tr');
      const isHead = hasHeader && ri === 0;
      for (let ci = 0; ci < cols; ci++) {
        const cell = document.createElement(isHead ? 'th' : 'td');
        cell.textContent = cells[ci] ?? '';
        tr.appendChild(cell);
      }
      table.appendChild(tr);
    });
    table.addEventListener('mousedown', (e) => {
      e.preventDefault();
      view.dispatch({ selection: { anchor: this.enterAt } });
      view.focus();
    });
    return table;
  }
  ignoreEvent() {
    return false;
  }
}

// ---------------------------------------------------------------- helpers

interface SelLike {
  ranges: readonly { from: number; to: number }[];
}

/**
 * 光标（空选区）或选区落在 [f, t] 内时返回 true —— 该构造要还原成源码。
 *
 * 端点算在内是有意的：对 `**bold**` 边界就是文字的一部分；对代码块/表格，
 * 光标停在末字符之后（t）表示用户正在编辑最后一格，不该被折叠掉。
 * 光标要离开得往**块外**的行走（f 之前 / t 之后），那才会折叠。
 */
function cursorInside(sel: SelLike, f: number, t: number): boolean {
  for (const r of sel.ranges) {
    if (r.from <= f && r.to >= t) return true;
    if (r.from === r.to && r.from >= f && r.from <= t) return true;
  }
  return false;
}

/** 从 `from` 起跳过空白，用于把 `## ` 里的 `#` 和尾随空格一起藏掉。 */
function skipSpaces(doc: Text, from: number): number {
  let i = from;
  const lineEnd = doc.lineAt(from).to;
  while (i < lineEnd && doc.sliceString(i, i + 1) === ' ') i++;
  return i;
}

const HEADING_CLASS: Record<string, string> = {
  ATXHeading1: 'md-lp-h1',
  ATXHeading2: 'md-lp-h2',
  ATXHeading3: 'md-lp-h3',
  ATXHeading4: 'md-lp-h4',
  ATXHeading5: 'md-lp-h5',
  ATXHeading6: 'md-lp-h6',
};

// ---------------------------------------------------------------- builder

/**
 * CM6 的 Decoration 是 RangeValue，**不携带位置**（实测 from/to 均为 undefined），
 * 位置只存在于 RangeSetBuilder 里。所以先攒 {from,to,deco}，排序后交给 builder。
 *
 * 注意签名只吃 EditorState：装饰必须由 StateField 提供（见 livePreview 的注释），
 * 而 StateField 的 update 拿不到 EditorView。
 */
interface Pending {
  from: number;
  to: number;
  deco: Decoration;
}

function buildDecorations(state: EditorState): DecorationSet {
  const pending: Pending[] = [];
  const doc = state.doc;
  const sel = state.selection;
  const tree = ensureSyntaxTree(state, state.doc.length, 1000) ?? syntaxTree(state);

  const add = (f: number, t: number, deco: Decoration) => {
    if (f < t) pending.push({ from: f, to: t, deco });
  };
  const replace = (f: number, t: number) => add(f, t, Decoration.replace({}));
  const replaceWith = (f: number, t: number, w: WidgetType) => add(f, t, Decoration.replace({ widget: w }));
  const mark = (f: number, t: number, cls: string, attrs?: Record<string, string>) =>
    add(f, t, Decoration.mark(attrs ? { class: cls, attributes: attrs } : { class: cls }));

  tree.iterate({
    enter(node) {
      const f = node.from;
      const t = node.to;
      switch (node.name) {
          // ---- 标题：`#` 连同尾随空格一起藏起来，整行按层级放大 ----
          case 'ATXHeading1':
          case 'ATXHeading2':
          case 'ATXHeading3':
          case 'ATXHeading4':
          case 'ATXHeading5':
          case 'ATXHeading6': {
            mark(f, t, HEADING_CLASS[node.name]);
            if (!cursorInside(sel, f, t)) {
              const hash = node.node.getChildren('HeaderMark')[0];
              replace(f, skipSpaces(doc, hash ? hash.to : f));
            }
            return false;
          }

          // ---- 强调：** / * / ~~ ----
          case 'StrongEmphasis':
            mark(f, t, 'md-lp-strong');
            break;
          case 'Emphasis':
            mark(f, t, 'md-lp-em');
            break;
          case 'Strikethrough':
            mark(f, t, 'md-lp-strike');
            break;
          case 'EmphasisMark':
          case 'StrikethroughMark':
            if (!cursorInside(sel, f, t)) replace(f, t);
            return false;

          // ---- 行内代码：保留反引号（Typora 也不藏），只做样式 ----
          case 'InlineCode':
            mark(f, t, 'md-lp-code');
            return false;

          // ---- 链接：折叠成纯文本，URL 挪到 title 上悬停可见 ----
          case 'Link': {
            const kids: { name: string; from: number; to: number }[] = [];
            for (let c = node.node.firstChild; c; c = c.nextSibling) {
              kids.push({ name: c.name, from: c.from, to: c.to });
            }
            const urlKid = kids.find((k) => k.name === 'URL');
            const url = urlKid ? doc.sliceString(urlKid.from, urlKid.to) : '';
            if (!cursorInside(sel, f, t)) {
              for (const k of kids) {
                if (k.name === 'LinkMark' || k.name === 'URL' || k.name === 'LinkTitle') replace(k.from, k.to);
              }
            }
            mark(f, t, 'md-lp-link', url ? { title: url } : undefined);
            return false;
          }
          // 裸 URL（GFM 自动链接）：本身是内容，染成链接色即可
          case 'URL':
            mark(f, t, 'md-lp-link');
            return false;

          // ---- 引用：藏 `>` 连同其后空格，整段留竖线 ----
          case 'QuoteMark':
            if (!cursorInside(sel, f, t)) replace(f, skipSpaces(doc, t));
            return false;
          case 'Blockquote':
            mark(f, t, 'md-lp-quote');
            return false;

          // ---- 列表：无序换圆点 widget；有序保留数字（`1.` 本身就是编号） ----
          case 'ListMark': {
            // 注意父链深度：lezer-markdown 里 ListMark 的父节点是 ListItem，
            // OrderedList / BulletList 还要再往上数一级。写错一级，有序列表也会被
            // 判成无序换成圆点 —— 表现就是「有序列表显示成点，没有阿拉伯数字」。
            const listName = node.node.parent?.parent?.name;
            if (listName !== 'BulletList') break;
            if (!cursorInside(sel, f, t)) replaceWith(f, t, new BulletWidget());
            else mark(f, t, 'md-lp-punct');
            break;
          }

          // ---- 任务清单：`[x]` 换成可点的方框 ----
          case 'TaskMarker':
            replaceWith(f, t, new CheckboxWidget(doc.sliceString(f, t) === '[x]', f));
            return false;

          // ---- 分隔线 ----
          case 'HorizontalRule':
            replaceWith(f, t, new HrWidget());
            return false;

          // ---- 代码块 / 表格：光标不在块内就折叠成渲染态，进去才展开源码 ----
          case 'FencedCode':
          case 'CodeBlock': {
            mark(f, t, 'md-lp-codeblock');
            if (cursorInside(sel, f, t)) break;
            // 必须走 firstChild 链而不是 getChildren()：lezer 无参 getChildren()
            // 实测返回空数组（带 type 参数才有结果），会静默拿不到 CodeText。
            let textNode: { from: number; to: number } | null = null;
            let infoText = '';
            for (let c = node.node.firstChild; c; c = c.nextSibling) {
              if (c.name === 'CodeText') textNode = { from: c.from, to: c.to };
              else if (c.name === 'CodeInfo') infoText = doc.sliceString(c.from, c.to);
            }
            const code = textNode ? doc.sliceString(textNode.from, textNode.to) : '';
            // 光标落点放在代码正文开头（跳过 ``` 围栏），进去就能直接改
            replaceWith(f, t, new CodeBlockWidget(code, infoText, textNode ? textNode.from : f));
            return false;
          }
          case 'Table': {
            mark(f, t, 'md-lp-table-src');
            if (cursorInside(sel, f, t)) break;
            replaceWith(f, t, new TableWidget(doc.sliceString(f, t), f + 1));
            return false;
          }
        }
    },
  });

  // RangeSetBuilder 有两重排序约束（缺一即抛 "Ranges must be added sorted by `from` position and `startSide`"）：
  //  1) from 升序；
  //  2) 同 from 时按 startSide 升序 —— 实测 mark 是 500000000，replace 是 499999999，
  //     所以 replace 必须排在同起点的 mark **之前**（代码块/表格正是先 mark 再 replace）。
  //  3) 前两条都相同时才用 to 升序。
  pending.sort((a, b) => a.from - b.from || a.deco.startSide - b.deco.startSide || a.to - b.to);
  const builder = new RangeSetBuilder<Decoration>();
  for (const p of pending) builder.add(p.from, p.to, p.deco);
  return builder.finish();
}

// ---------------------------------------------------------------- extension

/**
 * 装进 CodeMirror 的 extensions 里。
 *
 * <p><b>为什么是 StateField 而不是 ViewPlugin</b>
 * 代码块和表格要 replace 成跨行的 widget，而 CM6 对 ViewPlugin 提供的装饰打了
 * `disallowBlockEffects` 标记，跨行 replace 会直接抛
 * "Decorations that replace line breaks may not be specified via plugins"，
 * 插件整个崩掉。所以装饰必须由 StateField 经 `EditorView.decorations.from` 提供。
 *
 * 代价是 StateField 的 update 拿不到 EditorView —— widget 因此改成在
 * `toDOM(view)` 里接收视图，而不是构造时注入。
 */
const livePreviewField = StateField.define<DecorationSet>({
  create: (state) => buildDecorations(state),
  update(deco, tr) {
    // Transaction 上没有 selectionSet 标志，只能比对前后选区
    const before = tr.startState.selection.main;
    const after = tr.state.selection.main;
    const moved = before.from !== after.from || before.to !== after.to;
    // 语法解析是**异步推进**的：文档没动、光标没动，树也可能刚解析出新节点。
    // 位于文档后部的构造（表格最容易踩到）首帧往往还没解析到，
    // 漏了这一条，折叠态就会一直缺失，直到用户碰巧编辑了那块。
    const treeChanged = syntaxTree(tr.startState) !== syntaxTree(tr.state);
    if (!tr.docChanged && !moved && !treeChanged) return deco;
    return buildDecorations(tr.state);
  },
  provide: (f) => EditorView.decorations.from(f),
});

export function livePreview(): Extension {
  return livePreviewField;
}