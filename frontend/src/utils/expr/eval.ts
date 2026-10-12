/**
 * 表达式求值 —— 移植自 qiyuan `barda-core/src/eval/utils/string2Fn.tsx` 的
 * `DefaultParser`（字符串模式）。
 *
 * ---------------------------------------------------------------------------
 * 本次只移植字符串模式，JSON 模式（RelaxedJsonParser）刻意推迟，原因见下
 * ---------------------------------------------------------------------------
 * 上游 JSON 模式依赖 `really-relaxed-json`，且要求用户在 relaxed JSON 里内嵌
 * `{{}}`（写 `"options": {{$form.list}}`）。这对表单设计器是反直觉的：
 * 选项列表天然是「一组 {label, value}」，让用户手写宽松 JSON 再内嵌表达式，
 * 出错面远大于收益。M2 打算改成结构化绑定 —— options 数组里每项的 label /
 * value 各自是一个字符串模式表达式，复用本文件的能力且零新依赖。
 *
 * 上游的 `quoteLongNumberLiterals`（>16 位整数字面量防精度丢失）是为 JSON 模式
 * 服务的，本期不涉及 JSON，同样不需要。
 */

import { getDynamicStringSegments, isDynamicSegment } from './segments';
import { evalScript, type SandboxOption } from './sandbox';
import { getErrorMessage, ValueAndMsg, type ExprSegment } from './types';

/** 求值单个 `{{...}}` 内部表达式；失败不抛，降级为空串 + 错误信息 */
function evalSegment(
  content: string,
  context: Record<string, unknown>,
  segment: string,
  options?: SandboxOption,
): ValueAndMsg<unknown> {
  // `{{}}` / `{{ }}` 空表达式：不是错误，按成功处理（用户可能正在输入中）
  if (!content) {
    return new ValueAndMsg('', undefined, { segments: [{ value: segment, success: true }] });
  }
  try {
    const value = evalScript(content, context, options);
    return new ValueAndMsg(value, undefined, { segments: [{ value: segment, success: true }] });
  } catch (err) {
    return new ValueAndMsg('', getErrorMessage(err), {
      segments: [{ value: segment, success: false }],
    });
  }
}

class DefaultParser {
  private readonly segments: string[];
  private readonly results: ValueAndMsg<unknown>[] = [];

  constructor(
    unevaledValue: string,
    private readonly context: Record<string, unknown>,
    private readonly options?: SandboxOption,
  ) {
    this.segments = getDynamicStringSegments(unevaledValue.trim());
  }

  parse(): ValueAndMsg<unknown> {
    try {
      const object = this.parseObject();
      if (this.results.length === 0) {
        // 纯静态串：没有任何动态段，直接返回原文本（连 ValueAndMsg 包装都省了语义歧义）
        return new ValueAndMsg(object);
      }
      // 多个动态段时，聚合错误信息：编辑器需要知道「这一整串里有几段挂了」
      const firstError = this.results.find((r) => r.msg)?.msg;
      const segments: ExprSegment[] = this.results.flatMap((r) => r.extra?.segments ?? []);
      return new ValueAndMsg(object, firstError, { segments });
    } catch (err) {
      return new ValueAndMsg('', getErrorMessage(err));
    }
  }

  parseObject(): unknown {
    const values = this.segments.map((segment) =>
      isDynamicSegment(segment) ? this.evalDynamicSegment(segment) : segment,
    );
    // 关键分支：整串就是单个 `{{...}}` 时返回**原始类型**而非字符串 ——
    // `{{ $form.list }}` 应得到数组本身，`{{ 1 }}` 应得到数字 1，而不是 "1"。
    // 多段时才做字符串拼接。
    return values.length === 1 ? values[0] : values.join('');
  }

  private evalDynamicSegment(segment: string): unknown {
    const result = evalSegment(segment.slice(2, -2).trim(), this.context, segment, this.options);
    this.results.push(result);
    return result.value;
  }
}

/**
 * 求值一个可能含 `{{}}` 的字符串。
 *
 * - 纯静态串 → 原样返回，零开销（不建 Proxy、不跑 Function）
 * - 单个 `{{}}` → 返回原始类型的值
 * - 混合串 → 动态段求值后拼接，失败段降级为空串
 *
 * 求值失败**不抛异常**，错误装在 `.msg` 里。
 *
 * ⚠️ 每段只支持**单个表达式**，不支持分号分隔的多语句：
 * `{{ a; b }}` 会被包成 `return (a; b)` 触发语法错误。
 * 需要多语句（副作用、派生字段写回）时用 `allowContextMutation` 选项，
 * 但仍要写成单个表达式（如 `{{ ($form.c = $form.a + $form.b) }}`）。
 */
export function evalDefault(
  unevaledValue: string,
  context: Record<string, unknown>,
  options?: SandboxOption,
): ValueAndMsg<unknown> {
  return new DefaultParser(unevaledValue, context, options).parse();
}

/** 是否含 `{{}}` —— 渲染层用它决定要不要走求值路径 */
export function hasExpression(src: unknown): src is string {
  return typeof src === 'string' && src.includes('{{');
}

/**
 * 取文本值：求值失败时降级为静态兜底。
 *
 * 兜底策略是「保留原串里的静态文本」而非空串 —— 用户写
 * `你好 {{$form.name}}` 且 name 未定义时，显示「你好 」比显示空更合理。
 */
export function evalText(
  src: unknown,
  context: Record<string, unknown>,
  fallback = '',
  options?: SandboxOption,
): string {
  if (typeof src !== 'string' || src === '') return fallback;
  if (!hasExpression(src)) return src;

  const result = evalDefault(src, context, options);
  if (result.hasError()) return stripExpressions(src);
  if (result.value == null) return '';
  return typeof result.value === 'string' ? result.value : String(result.value);
}

/** 剥掉所有 `{{...}}`，只留静态文本（求值失败时的兜底用） */
export function stripExpressions(src: string): string {
  return getDynamicStringSegments(src)
    .filter((s) => !isDynamicSegment(s))
    .join('');
}