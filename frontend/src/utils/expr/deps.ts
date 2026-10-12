/**
 * 表达式引用分析 —— 移植自 qiyuan `barda-core/src/eval/utils/evaluate.tsx`
 * 的 `getIdentifiers` / `rename` 两段，并针对本项目的 `$form.<key>` 引用
 * 模型做了收窄与加强。
 *
 * 为什么运行时用 Vue 响应式、这块却还要保留：
 * Vue 的 `computed` 能做到「值变了自动重算」，但做不到「这段表达式引用了
 * 一个根本不存在的字段」这种**设计期诊断** —— 运行时它只会安静地拿到
 * undefined。编辑器要画波浪线、要提示变量列表，就得静态扫一遍标识符。
 */

import { getDynamicStringSegments, isDynamicSegment } from './segments';

/** 从 JS 片段里提取标识符（含 `a.b.c` 与 `a['b']` 形式） */
export function getIdentifiers(jsSnippet: string): string[] {
  const ret: string[] = [];
  const commonIds = jsSnippet.match(/[a-zA-Z_$][a-zA-Z_$0-9.[\]]*/g);
  if (commonIds) ret.push(...commonIds);

  // 方括号取值 a[b] 里的 b 是独立标识符，需递归展开
  const indexIds: string[] = [];
  for (const i of jsSnippet.match(/\[[a-zA-Z_][a-zA-Z_0-9\[\].]*\]/g) ?? []) {
    indexIds.push(...getIdentifiers(i.slice(1, -1)));
  }
  ret.push(...indexIds);

  // 一个标识符都提不出（如纯字面量 "abc"）→ 原样返回，交由调用方判为「无引用」
  return ret.length === 0 ? [jsSnippet] : ret;
}

/** 逐个 `{{}}` 段提取标识符 */
export function getExpressionIdentifiers(src: string): string[] {
  const out: string[] = [];
  for (const segment of getDynamicStringSegments(src)) {
    if (isDynamicSegment(segment)) {
      out.push(...getIdentifiers(segment.slice(2, -2)));
    }
  }
  return out;
}

/**
 * 取出表达式引用的**表单字段 key**。
 *
 * 只认 `$form.<key>` 这一种形式 —— 这就是本项目约定的表单数据命名空间。
 * 其它标识符（Math / $user / 内联函数参数）不是字段引用，不参与。
 */
export function referencedFieldKeys(src: string): string[] {
  const keys = new Set<string>();
  for (const id of getExpressionIdentifiers(src)) {
    const m = /^\$form\.([a-zA-Z_$][\w$]*)/.exec(id);
    if (m) keys.add(m[1]);
  }
  return [...keys];
}

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * 把表达式里对 `oldKey` 的引用全部改写成 `newKey`。
 *
 * 只在「完整路径段」上替换：前后都不能是标识符字符。
 * 这样 `$form.userName` → `$form.userName_copy` 成立，而 `userNameX`
 * （后接标识符字符）和 `myuserName`（前接标识符字符）都不会被误伤。
 *
 * 上下游差异：qiyuan 的 `changeDependName` 用的是「前缀匹配 + accessor 拼接」，
 * 只能处理 `$form.a.b` 里根名是依赖项的场景。表单字段引用固定是
 * `$form.<key>` 的二层结构，段匹配更准确也更好读。
 */
export function renameFieldKey(src: string, oldKey: string, newKey: string): string {
  if (!src || !oldKey || !newKey || oldKey === newKey) return src;

  const pattern = new RegExp(`(?<![\\w$])${escapeRegExp(oldKey)}(?![\\w$])`, 'g');

  // 只改写 `{{}}` 段内的内容，静态文本保持原样
  return getDynamicStringSegments(src)
    .map((segment) => (isDynamicSegment(segment) ? segment.replace(pattern, newKey) : segment))
    .join('');
}

/** 批量重命名：单个字段的所有表达式属性一起改（复制字段时用） */
export function renameFieldKeyInAll(
  attrs: Partial<Record<string, string>>,
  oldKey: string,
  newKey: string,
): void {
  for (const k of Object.keys(attrs)) {
    const v = attrs[k];
    if (typeof v === 'string') attrs[k] = renameFieldKey(v, oldKey, newKey);
  }
}

/**
 * 找出表达式里引用了、但当前 schema 里不存在的字段 key。
 *
 * 用于设计器的「未定义字段」提示。返回去重后的 key 列表，空数组表示引用完整。
 */
export function undefinedFieldRefs(
  src: string,
  knownKeys: Iterable<string>,
): string[] {
  const known = new Set(knownKeys);
  return referencedFieldKeys(src).filter((k) => !known.has(k));
}