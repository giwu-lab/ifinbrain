/**
 * `{{...}}` 动态段解析 —— 移植自 qiyuan `barda-core/src/eval/utils/segmentUtils.ts`。
 *
 * 逻辑与上游逐行一致（含嵌套花括号的花括号深度配对算法），无第三方依赖。
 *
 * 为什么不能用一条正则搞定：`{{ cond ? {a:1} : {} }}` 里对象字面量的
 * 右花括号会提前把 `}}` 凑出来。`DYNAMIC_SEGMENT_REGEX` 只用于「快速判断
 * 是否含动态段」，真正切分走 `getDynamicStringSegments()` 的深度计数。
 */

const DYNAMIC_SEGMENT_REGEX = /{{([\s\S]*?)}}/;

/** 判断字符串里是否含 `{{...}}` 动态段（快速路径，不保证段边界正确） */
export function isDynamicSegment(segment: string): boolean {
  return DYNAMIC_SEGMENT_REGEX.test(segment);
}

/**
 * 把字符串切成「静态文本」与「`{{...}}` 动态段」两类片段。
 *
 * 支持嵌套花括号：从 `{{` 之后开始累加 `{`、递减 `}`，
 * 取「回到起始花括号深度」的那个 `}}` 作为真正的结束位置。
 *
 * @example
 *   '你好 {{$form.name}}，共 {{list.length}} 项'
 *   → ['你好 ', '{{$form.name}}', '，共 ', '{{list.length}}', ' 项']
 */
export function getDynamicStringSegments(input: string): string[] {
  const segments: string[] = [];
  let position = 0;
  let start = input.indexOf('{{');

  while (start >= 0) {
    let i = start + 2;
    // 吃掉 `{{{` 的额外花括号，记录起始深度
    while (i < input.length && input[i] === '{') i++;
    let end = input.indexOf('}}', i);
    if (end < 0) break;

    const nextStart = input.indexOf('{{', end + 2);
    const maxIndex = nextStart >= 0 ? nextStart : input.length;
    const maxStartOffset = i - start - 2;
    let sum = i - start;
    let minValue = Number.MAX_VALUE;
    let minOffset = Number.MAX_VALUE;

    for (; i < maxIndex; i++) {
      switch (input[i]) {
        case '{':
          sum++;
          break;
        case '}':
          sum--;
          if (input[i - 1] === '}') {
            // 深度越接近起始深度、且越靠近起始位置 → 越可能是真正的闭合点
            const offset = Math.min(Math.max(sum, 0), maxStartOffset);
            const value = Math.abs(sum - offset);
            if (value < minValue || (value === minValue && offset < minOffset)) {
              minValue = value;
              minOffset = offset;
              end = i + 1;
            }
          }
          break;
      }
    }

    segments.push(input.slice(position, start + minOffset), input.slice(start + minOffset, end));
    position = end;
    start = nextStart;
  }

  segments.push(input.slice(position));
  // 过滤空串：相邻动态段 / 首尾动态段会产生空文本碎片
  return segments.filter((t) => t);
}