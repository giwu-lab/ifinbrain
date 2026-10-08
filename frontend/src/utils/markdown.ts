/**
 * Markdown 渲染 —— 全站唯一入口。
 *
 * <p><b>为什么必须过 DOMPurify</b>：笔记正文是用户输入，直接 v-html 渲染就是存储型 XSS。
 * 后端只存原始 markdown、从不返回渲染后的 HTML（见 Note 实体注释），
 * 渲染责任与转义责任都在这里 —— 所以**任何页面都不要自己 v-html 笔记内容**。
 *
 * <p>白名单按 marked 的默认集再收紧：禁掉 javascript: 协议、事件属性、style，
 * 外链强制 noreferrer（否则 target=_blank 会把 referrer 带给外站）。
 *
 * <p>标题 id：**大纲与渲染出的 HTML 从同一个字符串派生**（都基于渲染结果），
 * 而不是各自从 markdown 源码猜 slug —— 否则改一处就要改两处，对不上时锚点是静默失效的。
 */
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import type { Config as PurifyConfig } from 'dompurify';

marked.setOptions({
  gfm: true,
  breaks: true, // 业务文档里单换行就该换行，不必两个空行
});

const PURIFY_CONFIG: PurifyConfig = {
  ALLOWED_TAGS: [
    'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
    'p', 'br', 'hr',
    'strong', 'em', 'del', 'code', 'pre', 'blockquote',
    'ul', 'ol', 'li',
    'a', 'img',
    'table', 'thead', 'tbody', 'tr', 'th', 'td',
    'input', // 任务清单 - [ ] / - [x]
  ],
  ALLOWED_ATTR: ['href', 'title', 'src', 'alt', 'align', 'type', 'checked', 'disabled', 'target', 'id'],
  ALLOWED_URI_REGEXP: /^(?:https?:|mailto:|tel:|#|\/|\.\/|\.\.\/)/i,
  FORBID_TAGS: ['style', 'script', 'iframe', 'object', 'embed', 'form', 'svg', 'math'],
  FORBID_ATTR: ['style', 'onerror', 'onload', 'onclick', 'srcset', 'formaction'],
  ADD_ATTR: ['target'],
};

/** id 前缀：与 extractOutline 共用，保证锚点必然对得上。 */
const HEADING_ID_PREFIX = 'md-h-';

/** markdown → 安全 HTML。永远用它渲染笔记，不要自己 v-html。 */
export function renderMarkdown(source: string | null | undefined): string {
  if (!source) return '';
  const raw = marked.parse(source, { async: false }) as string;
  // marked 不给标题加 id，这里按出现顺序补上，供右侧大纲跳转
  let n = 0;
  const withIds = raw.replace(/<h([1-6])(\s[^>]*)?>/gi, (full, _lvl, attrs = '') =>
    attrs.includes('id=') ? full : `<h${_lvl}${attrs} id="${HEADING_ID_PREFIX}${n++}">`);
  const clean = String(DOMPurify.sanitize(withIds, PURIFY_CONFIG));
  // 外链一律新窗口打开且切断 referrer
  return clean.replace(/<a /g, '<a target="_blank" rel="noopener noreferrer" ');
}

export interface OutlineItem {
  level: number;
  text: string;
  id: string;
}

export interface SourceOutlineItem {
  level: number;
  text: string;
  /** 1-based 行号；编辑态靠它让编辑器滚动过去 */
  line: number;
}

/**
 * 编辑态大纲：直接扫 markdown **源码**里的 ATX 标题。
 *
 * 与 {@link extractOutline} 的分工：那个吃渲染后的 HTML，给**只读态**提供 DOM 锚点 id；
 * 这个吃源码，给**编辑态**提供行号 —— 渲染结果里根本没有行号的概念。
 * 两边都只认 ATX 标题（# ~ ######），与 CodeMirror 的解析口径保持一致。
 *
 * 围栏代码块内的 `# xxx` 要跳过，否则大纲会混进代码内容。
 */
export function extractSourceOutline(source: string | null | undefined): SourceOutlineItem[] {
  if (!source) return [];
  const out: SourceOutlineItem[] = [];
  const lines = source.split('\n');
  let inFence = false;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (/^\s{0,3}(?:`{3,}|~{3,})/.test(line)) {
      inFence = !inFence;
      continue;
    }
    if (inFence) continue;
    const m = /^(#{1,6})\s+(.+?)\s*#*\s*$/.exec(line);
    if (!m) continue;
    out.push({ level: m[1].length, text: m[2], line: i + 1 });
  }
  return out;
}

/**
 * 大纲（右侧目录）。
 *
 * 入参必须是 {@link renderMarkdown} 的输出 —— 从渲染结果里正则抽 id，
 * 这样目录项和锚点天然一致；传 markdown 源码会静默给出错位的锚点。
 */
export function extractOutline(renderedHtml: string | null | undefined): OutlineItem[] {
  if (!renderedHtml) return [];
  const out: OutlineItem[] = [];
  const re = /<h([1-3])[^>]*\sid="([^"]+)"[^>]*>([\s\S]*?)<\/h\1>/gi;
  let m: RegExpExecArray | null;
  while ((m = re.exec(renderedHtml)) !== null) {
    const text = m[3].replace(/<[^>]+>/g, '').trim();
    if (!text) continue;
    out.push({ level: Number(m[1]), text, id: m[2] });
  }
  return out;
}