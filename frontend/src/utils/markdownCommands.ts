/**
 * 编辑快捷输入 —— 斜杠命令、选区浮动工具条、格式快捷键、列表自动续行。
 *
 * 纸面化之后工具条被去掉了（格式用 Markdown 标记本身表达），
 * 但"打字要快"这件事得由键盘补回来，这里就是补这一块。
 *
 * 与 markdownLivePreview 的分工：那个管**显示**（标记怎么渲染），
 * 这个管**输入**（敲什么键会变成什么）。
 */
import { EditorView, ViewPlugin } from '@codemirror/view';
import { StateEffect, StateField, type EditorState, type Extension } from '@codemirror/state';
import { isTableDelimiter, splitTableRow } from './markdownTable';

// ================================================================ 选区格式化

/**
 * 用 before/after 包住选区；没有选区时插入空对并把光标放中间。
 * 有选区时**保持原文选中** —— 这样连按 Ctrl+B、Ctrl+I 能连续套两层。
 */
export function wrapSelection(view: EditorView, before: string, after = before, placeholder = ''): boolean {
  const sel = view.state.selection.main;
  const { from, to } = sel;
  const text = view.state.doc.sliceString(from, to);
  const inner = text || placeholder;
  view.dispatch({
    changes: { from, to, insert: before + inner + after },
    selection: text
      ? { anchor: from + before.length, head: from + before.length + text.length }
      : { anchor: from + before.length },
  });
  view.focus();
  return true;
}

/**
 * 给选区覆盖的每一行加/去行首前缀。
 * prefix 可以是函数（有序列表要按原编号递增），参数是相对首行的序号。
 */
export function toggleLinePrefix(
  view: EditorView,
  prefix: string | ((n: number) => string),
  placeholder: string,
): boolean {
  const sel = view.state.selection.main;
  const first = view.state.doc.lineAt(sel.from);
  const last = view.state.doc.lineAt(sel.to);
  const lines: string[] = [];
  for (let ln = first.number; ln <= last.number; ln++) {
    const text = view.state.doc.line(ln).text;
    const p = typeof prefix === 'function' ? prefix(ln - first.number + 1) : prefix;
    lines.push(text.startsWith(p) ? text.slice(p.length) : p + (text || placeholder));
  }
  // 加了前缀就把光标放到前缀之后（接着打字才是往正文里写），去掉了就落行首
  const p1 = typeof prefix === 'function' ? prefix(1) : prefix;
  const anchor = lines[0].startsWith(p1) ? first.from + p1.length : first.from;
  view.dispatch({
    changes: { from: first.from, to: last.to, insert: lines.join('\n') },
    selection: { anchor },
  });
  view.focus();
  return true;
}

/**
 * 回车时续列表：`- 买牛奶` 回车 → 新一行自动带上 `- `；
 * 有序列表编号自动 +1；空列表项回车 → 退出列表（去掉前缀）。
 * 这是"快捷输入"里回报最高的一条，没有它连打三条列表项都是体力活。
 */
export function continueListItem(view: EditorView): boolean {
  const state = view.state;
  const sel = state.selection.main;
  if (!sel.empty) return false;
  const line = state.doc.lineAt(sel.head);
  if (sel.head !== line.to) return false; // 光标不在行尾不动

  const ordered = /^(\s*)(\d+)([.)])(\s+)/.exec(line.text);
  if (ordered) {
    const [full, indent, num, delim] = ordered;
    if (!line.text.slice(full.length).trim()) {
      view.dispatch({ changes: { from: line.from, to: line.to, insert: indent } });
      view.focus();
      return true;
    }
    view.dispatch({ changes: { from: line.to, insert: `\n${indent}${Number(num) + 1}${delim} ` } });
    view.focus();
    return true;
  }

  // 无序 + 任务清单：- / * / + / - [ ]
  const bullet = /^(\s*)([-*+])(\s+)(\[[ xX]\]\s+)?/.exec(line.text);
  if (bullet) {
    const [full, indent, mark, , task] = bullet;
    if (!line.text.slice(full.length).trim()) {
      view.dispatch({ changes: { from: line.from, to: line.to, insert: indent } });
      view.focus();
      return true;
    }
    const next = task ? `${indent}${mark} [ ] ` : `${indent}${mark} `;
    view.dispatch({ changes: { from: line.to, insert: `\n${next}` } });
    view.focus();
    return true;
  }

  return false;
}

/**
 * 回车时给「看起来像表头」的行补一行 GFM 分隔行。
 *
 * 分隔行的格式是硬要求（每格只能有 - : 空格），漏了整段就退化成普通段落，
 * 所有渲染器都不认。这恰恰是最容易手滑的一步 —— 手打 `| --- | --- |` 既枯燥
 * 又容易写成 `| ---ASDF- |`，所以直接替用户补上。
 *
 * 判定与渲染共用 utils/markdownTable.ts，避免"补出来的行渲染器却不认"。
 */
export function continueTableRow(view: EditorView): boolean {
  const state = view.state;
  const sel = state.selection.main;
  if (!sel.empty) return false;
  const line = state.doc.lineAt(sel.head);
  if (sel.head !== line.to) return false; // 不在行尾不动

  const text = line.text;
  if (!text.includes('|')) return false;
  if (isTableDelimiter(text)) return false; // 当前行就是分隔行

  // 下一行已经是分隔行就别重复插
  if (line.to < state.doc.length) {
    const nextLine = state.doc.lineAt(line.to + 1);
    if (isTableDelimiter(nextLine.text)) return false;
  }

  const cells = splitTableRow(text).filter((c, i, arr) => !(i === arr.length - 1 && c === ''));
  if (cells.length < 2) return false;

  const sep = `| ${cells.map(() => '---').join(' | ')} |`;
  view.dispatch({ changes: { from: line.to, insert: `\n${sep}` } });
  view.focus();
  return true;
}

/** 在光标处插入一段多行片段（表格这类），光标落到 offset。 */
export function insertBlock(view: EditorView, snippet: string, offset: number): boolean {
  const from = view.state.selection.main.from;
  view.dispatch({
    changes: { from, insert: snippet },
    selection: { anchor: from + offset },
  });
  view.focus();
  return true;
}

// ================================================================ 浮层基座

/**
 * 浮层基座：挂在 view.dom 下用 absolute 定位。
 *
 * 为什么不用 CM6 的 showTooltip facet：它收的是**静态** Tooltip 值（`pos` 是数字
 * 而非函数），做不到"随光标实时出现/消失"，而这两块面板都得跟着状态开关。
 *
 * 为什么挂 view.dom 而不是 document.body：挂进去才能吃到组件 scoped 的 `:deep()` 样式。
 * 代价是坐标要从视口系换算到编辑器系 —— `coordsAtPos` 返回的是视口坐标
 * （CM6 内部同样要减 `getBoundingClientRect().left`），减掉编辑器自身偏移即可。
 */
abstract class FloatingPanel {
  readonly dom: HTMLElement;

  private lastPos = 0;
  private lastAbove = false;
  /** 每次 place/hide 自增；write 阶段用它判断这次定位是否已被作废 */
  private token = 0;
  private measured: { left: number; top: number; bottom: number } | null = null;
  private measuredWidth = 0;
  private measuredHeight = 0;

  constructor(readonly view: EditorView) {
    this.dom = document.createElement('div');
    this.dom.className = 'cm-float-panel';
    this.dom.style.display = 'none';
    view.dom.appendChild(this.dom);
    view.scrollDOM.addEventListener('scroll', this.onScroll);
  }

  private onScroll = () => {
    // 滚动时跟着挪，否则面板会钉在原地
    if (this.dom.style.display !== 'none') this.place(this.lastPos, this.lastAbove);
  };

  /**
   * 定位。**不能在 ViewPlugin.update() 里直接读布局** ——
   * CM6 会抛 "Reading the editor layout isn't allowed during an update" 并把插件标记为崩溃，
   * 面板就永远停在隐藏态。必须走 requestMeasure：read 阶段量，write 阶段写样式。
   *
   * @param above 只是**偏好方向**，不是硬性要求。面板是 `position:absolute` 挂在 `view.dom` 上，
   *   而外层 `.note-writer` 是 `overflow:hidden` —— 选区在首行时若硬往上方摆，
   *   算出的 top 是负的，整条工具栏会被容器裁掉只剩一条边。所以要按实际剩余空间翻面。
   */
  protected place(pos: number, above: boolean) {
    this.lastPos = pos;
    this.lastAbove = above;
    const mine = ++this.token;
    this.view.requestMeasure({
      read: () => {
        // 得先显示才量得到尺寸，所以这里要破一次例改样式
        this.dom.style.display = '';
        const box = this.dom.getBoundingClientRect();
        this.measuredWidth = box.width;
        this.measuredHeight = box.height;
        try {
          this.measured = this.view.coordsAtPos(pos) ?? null;
        } catch {
          // 量不到坐标就别硬摆，宁可隐藏也不要崩掉整个面板
          this.measured = null;
        }
      },
      write: () => {
        // 期间已经 hide 或被更新的 place 取代 → 这次定位作废
        if (mine !== this.token) return;
        const c = this.measured;
        if (!c) return this.hide();
        const base = this.view.dom.getBoundingClientRect();
        const GAP = 6;
        // 候选位置换算到编辑器坐标系；编辑器可视区就是裁切区，超出即被切掉
        const topAbove = c.top - base.top - this.measuredHeight - GAP;
        const topBelow = c.bottom - base.top + GAP;
        const fitsAbove = topAbove >= 0;
        const fitsBelow = topBelow + this.measuredHeight <= base.height;
        // 偏好方向放得下就用它；放不下翻到另一侧；两侧都放不下才沿用偏好（下面夹取兜底）
        const useAbove = above ? fitsAbove || !fitsBelow : !fitsBelow && fitsAbove;
        // 夹取：选区贴着编辑器上下边缘时，保证面板完整可见而不是被裁一半
        const top = Math.max(0, Math.min(useAbove ? topAbove : topBelow, base.height - this.measuredHeight));
        // 横向同理：贴右边缘时整体左移，别让尾部按钮被切掉
        let left = c.left - base.left;
        if (left + this.measuredWidth > base.width) left = base.width - this.measuredWidth;
        if (left < 0) left = 0;
        this.dom.style.left = `${left}px`;
        this.dom.style.top = `${top}px`;
      },
    });
  }

  hide() {
    this.token++;
    this.dom.style.display = 'none';
  }
}

// ================================================================ 斜杠命令

export interface SlashCommand {
  /** 匹配用；同时作为 i18n key 后缀 */
  id: string;
  title: string;
  /** 插入的 Markdown 片段 */
  snippet: string;
  /**
   * 光标相对片段起点的落点，缺省落在末尾。
   * `****` 这种成对标记必须落中间 —— 否则用户还得自己把光标挪回去。
   */
  cursorOffset?: number;
  /** 额外的匹配词（中文标题已能匹配大部分情况） */
  keywords?: string;
}

export interface SlashState {
  /** `/` 的位置 */
  from: number;
  to: number;
  query: string;
  index: number;
}

const setSlash = StateEffect.define<SlashState | null>();
const setIndex = StateEffect.define<number>();

/** 只认行首或空白后的 `/`，避免正文里的 "and/or" 把菜单弹出来 */
function detectSlash(state: EditorState): SlashState | null {
  const sel = state.selection.main;
  if (!sel.empty) return null;
  const line = state.doc.lineAt(sel.head);
  const before = line.text.slice(0, sel.head - line.from);
  const m = /(?:^|\s)\/([^\s/]*)$/.exec(before);
  if (!m) return null;
  return { from: sel.head - m[1].length - 1, to: sel.head, query: m[1], index: 0 };
}

const slashField = StateField.define<SlashState | null>({
  create: () => null,
  update(value, tr) {
    for (const e of tr.effects) {
      if (e.is(setSlash)) return e.value;
      if (e.is(setIndex) && value) return { ...value, index: e.value };
    }
    // Transaction 上没有 selectionSet 标志，只能比对前后光标
    const before = tr.startState.selection.main;
    const after = tr.state.selection.main;
    const moved = before.head !== after.head || before.empty !== after.empty;
    if (!tr.docChanged && !moved) return value;
    return detectSlash(tr.state);
  },
});

export function filterSlashCommands(cmds: SlashCommand[], query: string): SlashCommand[] {
  const q = query.trim().toLowerCase();
  if (!q) return cmds;
  return cmds.filter(
    (c) =>
      c.id.toLowerCase().includes(q) ||
      c.title.toLowerCase().includes(q) ||
      (c.keywords ?? '').toLowerCase().includes(q),
  );
}

function applySlashCommand(view: EditorView, cmd: SlashCommand) {
  const st = view.state.field(slashField, false);
  if (!st) return;
  const at = st.from + (cmd.cursorOffset ?? cmd.snippet.length);
  view.dispatch({
    changes: { from: st.from, to: st.to, insert: cmd.snippet },
    selection: { anchor: Math.min(at, st.from + cmd.snippet.length) },
    effects: setSlash.of(null),
  });
  view.focus();
}

class SlashPanel extends FloatingPanel {
  constructor(view: EditorView, private readonly get: () => SlashCommand[]) {
    super(view);
    this.sync();
  }

  update() {
    this.sync();
  }

  private sync() {
    const st = this.view.state.field(slashField, false);
    const items = st ? filterSlashCommands(this.get(), st.query) : [];
    if (!st || !items.length) {
      // 一并清空：hide() 只切 display，留着旧条目会让人以为菜单还在
      this.dom.textContent = '';
      return this.hide();
    }
    const index = Math.min(st.index, Math.max(items.length - 1, 0));
    this.dom.className = 'cm-float-panel cm-slash-menu';
    this.dom.textContent = '';
    items.forEach((c, i) => {
      const row = document.createElement('div');
      row.className = `cm-slash-item${i === index ? ' is-active' : ''}`;
      row.textContent = c.title;
      // 鼠标点选：与浮动工具条一致用 mousedown + preventDefault，
      // 不抢编辑器焦点，否则点一下选区就没了。命令直接从闭包里取，
      // 不必依赖 index，所以点击永远命中自己那一项。
      row.addEventListener('mousedown', (e) => {
        e.preventDefault();
        applySlashCommand(this.view, c);
      });
      // 悬停只切高亮、不发 transaction：发了会触发 sync 重建 DOM，反而闪烁
      row.addEventListener('mouseenter', () => {
        [...this.dom.children].forEach((n, idx) => n.classList.toggle('is-active', idx === i));
      });
      this.dom.appendChild(row);
    });
    this.place(st.from, false);
  }
}

/**
 * 斜杠命令面板。必须排在 defaultKeymap **之前** ——
 * Enter / Tab / 方向键要先被它截走，否则会被当普通编辑处理。
 */
export function slashCommandPanel(get: () => SlashCommand[]): Extension {
  return [
    slashField,
    ViewPlugin.define((view) => new SlashPanel(view, get)),
    EditorView.domEventHandlers({
      keydown: (event, view) => {
        const st = view.state.field(slashField, false);
        if (!st) return false;
        const items = filterSlashCommands(get(), st.query);
        if (!items.length) return false;
        const index = Math.min(st.index, items.length - 1);
        switch (event.key) {
          case 'ArrowDown':
            view.dispatch({ effects: setIndex.of((index + 1) % items.length) });
            return true;
          case 'ArrowUp':
            view.dispatch({ effects: setIndex.of((index - 1 + items.length) % items.length) });
            return true;
          case 'Enter':
          case 'Tab':
            applySlashCommand(view, items[index]);
            return true;
          case 'Escape':
            view.dispatch({ effects: setSlash.of(null) });
            return true;
          default:
            return false;
        }
      },
    }),
  ];
}

// ================================================================ 选区浮动工具条

export interface FormatAction {
  id: string;
  /** 按钮上的短字形 */
  label: string;
  /** 悬停提示；缺省回落到 label */
  tip?: string;
  run: (view: EditorView) => void;
}

class FormatToolbarPanel extends FloatingPanel {
  /** 已渲染的动作表；换语言时 computed 会给出新数组，识别到才重建 DOM */
  private renderedFor: FormatAction[] | null = null;

  constructor(view: EditorView, private readonly get: () => FormatAction[]) {
    super(view);
    this.sync();
  }

  update() {
    this.sync();
  }

  private sync() {
    const sel = this.view.state.selection.main;
    const actions = this.get();
    if (sel.empty || !actions.length) return this.hide();
    if (this.renderedFor !== actions) {
      this.dom.className = 'cm-float-panel cm-fmt-bar';
      this.dom.textContent = '';
      for (const a of actions) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'cm-fmt-btn';
        btn.textContent = a.label;
        btn.title = a.tip ?? a.label;
        // preventDefault：不抢走编辑器里的选区，连点还能接着套下一个格式
        btn.addEventListener('mousedown', (e) => {
          e.preventDefault();
          a.run(this.view);
          this.view.focus();
        });
        this.dom.appendChild(btn);
      }
      this.renderedFor = actions;
    }
    this.place(sel.from, true);
  }
}

/** 选中文字时在选区上方浮现一排格式按钮；光标态不显示（那时没有可作用的对象）。 */
export function formatToolbar(get: () => FormatAction[]): Extension {
  return ViewPlugin.define((view) => new FormatToolbarPanel(view, get));
}