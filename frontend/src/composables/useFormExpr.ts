/**
 * 表单表达式接入层 —— 把 `utils/expr` 的求值能力接到表单 schema 上。
 *
 * ---------------------------------------------------------------------------
 * 响应式是怎么来的（这里不需要 computed，也不需要自己写依赖图）
 * ---------------------------------------------------------------------------
 * `text()` 内部最终会走到沙箱 Proxy 的 `get` 陷阱，而陷阱里做的是
 * `Reflect.get($form, 'userName')` —— `$form` 是 FormRenderer 里的 reactive
 * formData，于是这次读取**被 Vue 的依赖追踪自动记下**。
 * 所以只要在模板 / computed 里调用：
 *
 *   {{ text(f.label, t('formDesigner.unnamed')) }}
 *
 * 用户改一下「姓名」输入框，所有引用了 `$form.<name>` 的 label / placeholder
 * 会自动重算。Barda 那套 Node + dependMap 就是干这件事的，Vue 已经内置了。
 *
 * ⚠️ 因此：**不要把返回值缓存进非响应式变量**，否则会切断追踪。
 */

import type { FormFieldSchema } from '@/api/formDesign';
import {
  evalDefault,
  evalText,
  hasExpression,
  referencedFieldKeys,
  undefinedFieldRefs,
} from '@/utils/expr';

/** 可承载 `{{}}` 的字段属性 */
/** 承载文本的表达式属性 —— 求值结果直接当字符串渲染 */
export type ExprTextAttr = 'label' | 'placeholder' | 'help';

/** 承载布尔条件的表达式属性 —— 求值结果只取真值假值 */
export type ExprBoolAttr = 'visibleWhen' | 'disabledWhen' | 'requiredWhen';

export type ExprAttr = ExprTextAttr | ExprBoolAttr;

export const EXPR_TEXT_ATTRS: readonly ExprTextAttr[] = ['label', 'placeholder', 'help'] as const;
export const EXPR_BOOL_ATTRS: readonly ExprBoolAttr[] = [
  'visibleWhen', 'disabledWhen', 'requiredWhen',
] as const;

/**
 * 全部可写表达式的属性。
 *
 * 校验、引用扫描、复制字段时的引用重写都按这份清单走 —— 新增表达式属性时
 * **只需把它加进 EXPR_TEXT_ATTRS 或 EXPR_BOOL_ATTRS**，三处逻辑自动覆盖。
 */
export const EXPR_ATTRS: readonly ExprAttr[] = [
  ...EXPR_TEXT_ATTRS, ...EXPR_BOOL_ATTRS,
] as const;

/** 表达式命名空间：字段引用一律写成 `$form.<key>` */
export const FORM_NS = '$form';

/** 除表单数据外额外注入表达式的变量（如 `$user`） */
export interface FormExprExtras {
  [key: string]: unknown;
}

/**
 * 一条表达式问题（设计器用来标红 / 提示）。
 *
 * 刻意只带**结构化信息**不带成品文案：`message` 若在这里写死中文，
 * 就会直接漏进 `el-alert` 违反 AGENTS.md §2 的 i18n 约束。文案在渲染层
 * 用 `t('formDesigner.expr.*', { key: detail })` 组装。
 */
export interface ExprIssue {
  /** 出问题的字段 id */
  fieldId: string;
  /** 字段 key（便于用户定位，可能为空） */
  fieldKey: string;
  /** 出问题的属性名 */
  attr: ExprAttr;
  /**
   * undefined-ref —— 引用了 schema 中不存在的字段，detail 是那个 key
   * eval-error   —— 表达式本身语法/求值出错，detail 是原始错误串（技术信息，不翻译）
   */
  kind: 'undefined-ref' | 'eval-error';
  detail: string;
}

export interface UseFormExprOptions {
  /**
   * 跳过沙箱，宿主全局走词法作用域。
   * 仅设计态高频重算可开；表单运行时务必保持沙箱。
   */
  noSandbox?: boolean;
}

/**
 * 表单表达式求值器。
 *
 * @param formData 活的表单数据（通常是 reactive 对象）；它就是 `$form` 的值
 * @param extras   额外注入的变量
 */
export function useFormExpr(
  formData: Record<string, unknown>,
  extras: FormExprExtras = {},
  options: UseFormExprOptions = {},
) {
  const sandboxOptions = options.noSandbox ? { noSandbox: true } : undefined;
  const extraKeys = Object.keys(extras);

  /** 每次重建上下文对象 —— 不缓存，保证 reactive 追踪读到的是最新值 */
  function context(): Record<string, unknown> {
    if (extraKeys.length === 0) return { [FORM_NS]: formData };
    return { [FORM_NS]: formData, ...extras };
  }

  /**
   * 求值一段可能含 `{{}}` 的文本。
   *
   * 必须在模板或 computed 内调用才有响应性（见文件头说明）。
   *
   * @param fallback 求值失败或无值时的兜底文案
   */
  function text(src: unknown, fallback = ''): string {
    return evalText(src, context(), fallback, sandboxOptions);
  }

  /** 求值单个字段的某个属性 */
  function fieldText(field: FormFieldSchema, attr: ExprAttr, fallback = ''): string {
    return text(field[attr], fallback);
  }

  /**
   * 求值布尔条件。
   *
   * 语义刻意保守 —— **任何异常路径都返回 false**：
   * 空表达式 / 非 `{{}}` 串 / 求值抛错 / 结果不是真值，一律 false。
   *
   * 为什么不用「出错时返回 true」：`visibleWhen` 出错时若返回 true，
   * 一个写坏的条件表达式会把字段显示出来，填表人填了却不提交；
   * 而返回 false 只是少显示一个字段，用户一眼能看出不对劲。
   * 「宁可少显示，不要静默算错」是这里的一致取舍。
   */
  function flag(src: unknown): boolean {
    if (!hasExpression(src)) return false;
    const result = evalDefault(String(src), context(), sandboxOptions);
    if (result.hasError()) return false;
    return result.value === true;
  }

  /** 字段是否应渲染（visibleWhen 未配 = 恒显示） */
  function isVisible(field: FormFieldSchema): boolean {
    return !field.visibleWhen ? true : flag(field.visibleWhen);
  }

  /** 字段是否应禁用（disabledWhen 未配 = 恒可编辑） */
  function isDisabled(field: FormFieldSchema): boolean {
    return !!field.disabledWhen && flag(field.disabledWhen);
  }

  /** 字段是否必填：字段级 required 与 requiredWhen 任一为真即必填 */
  function isRequired(field: FormFieldSchema): boolean {
    if (field.required === true) return true;
    return !!field.requiredWhen && flag(field.requiredWhen);
  }

  /** 该字段是否有任何属性承载了表达式（含布尔条件） */
  function hasAnyExpr(field: FormFieldSchema): boolean {
    return EXPR_ATTRS.some((attr) => hasExpression(field[attr]));
  }

  /** 该字段是否有**文本类**表达式 —— 只有这类才需要构造解析后的字段对象 */
  function hasTextExpr(field: FormFieldSchema): boolean {
    return EXPR_TEXT_ATTRS.some((attr) => hasExpression(field[attr]));
  }

  /**
   * 解析后的字段对象缓存。
   *
   * 为什么需要它：字段组件（TextField 等）内部直接读 `field.placeholder`，
   * 所以必须把解析后的字段传下去。但若每次渲染都 `{ ...field }`，用户每敲
   * 一个键就会给**所有**字段换一个新对象引用，触发全表单子组件重渲染 ——
   * 20 个字段的表单会明显卡。
   *
   * 这里按「解析结果是否真的变了」决定换不换引用：结果没变就复用上次的对象，
   * Vue 的 patch 就会跳过该子组件。无表达式的字段更是原样返回，零开销。
   */
  const resolvedCache = new Map<
    string,
    { label: string; placeholder: string; help: string; obj: FormFieldSchema }
  >();

  /**
   * 返回「文本表达式已解析」的字段对象，交给字段组件渲染。
   *
   * 只看文本类属性：只配了 visibleWhen 的字段，label 并没有表达式，
   * 不该为它白白构造一个新对象（那会让该子组件每次都重渲染）。
   */
  function resolve(field: FormFieldSchema): FormFieldSchema {
    if (!hasTextExpr(field)) return field;

    const label = text(field.label, '');
    const placeholder = text(field.placeholder, '');
    const help = text(field.help, '');

    const hit = resolvedCache.get(field.id);
    if (hit && hit.label === label && hit.placeholder === placeholder && hit.help === help) {
      return hit.obj;
    }

    const obj: FormFieldSchema = { ...field, label, placeholder, help };
    resolvedCache.set(field.id, { label, placeholder, help, obj });
    return obj;
  }

  /**
   * 取字段属性的原始求值结果（不做字符串化）。
   *
   * 给「默认值」这类需要保留类型的场景用 —— `{{ $form.list }}` 应拿到数组，
   * 而不是 "[object Object]"。
   */
  function fieldValue(field: FormFieldSchema, attr: ExprAttr): unknown {
    const src = field[attr];
    if (!hasExpression(src)) return src;
    const result = evalDefault(src, context(), sandboxOptions);
    return result.hasError() ? undefined : result.value;
  }

  /**
   * 列出当前表达式里所有「引用了但 schema 中不存在」的字段 key。
   * 与响应式无关，纯静态扫描，供设计器保存前校验。
   */
  function undefinedRefs(fields: FormFieldSchema[]): Map<string, string[]> {
    const known = new Set(fields.map((f) => f.key).filter(Boolean));
    const out = new Map<string, string[]>();
    for (const field of fields) {
      for (const attr of EXPR_ATTRS) {
        const src = field[attr];
        if (!hasExpression(src)) continue;
        const missing = undefinedFieldRefs(src, known);
        if (missing.length) {
          out.set(field.id, [...(out.get(field.id) ?? []), ...missing]);
        }
      }
    }
    return out;
  }

  /**
   * 扫描整张表单的表达式问题。
   *
   * 两类：引用了不存在的字段（设计期可捕获的绝大多数错误）+ 求值期语法错误。
   * 前者是静态扫描，与运行时数据无关，因此保存前就能报。
   */
  function issues(fields: FormFieldSchema[]): ExprIssue[] {
    const result: ExprIssue[] = [];
    const known = new Set(fields.map((f) => f.key).filter(Boolean));

    for (const field of fields) {
      for (const attr of EXPR_ATTRS) {
        const src = field[attr];
        if (!hasExpression(src)) continue;

        const missing = undefinedFieldRefs(src, known);
        for (const key of missing) {
          result.push({ fieldId: field.id, fieldKey: field.key, attr, kind: 'undefined-ref', detail: key });
        }

        // 语法错误：拿空数据试跑一次即可暴露，不依赖用户填了什么
        const probe = evalDefault(src, { [FORM_NS]: {} }, sandboxOptions);
        if (probe.hasError() && !missing.length) {
          result.push({ fieldId: field.id, fieldKey: field.key, attr, kind: 'eval-error', detail: probe.msg! });
        }
      }
    }
    return result;
  }

  /** 给编辑器补全用的候选引用列表 */
  function availableRefs(fields: FormFieldSchema[]): string[] {
    const refs = fields
      .filter((f) => f.key)
      .map((f) => `${FORM_NS}.${f.key}`);
    return [...extraKeys.map((k) => `${k}`), ...refs];
  }

  return {
    FORM_NS,
    context,
    text,
    fieldText,
    fieldValue,
    flag,
    isVisible,
    isDisabled,
    isRequired,
    hasAnyExpr,
    hasTextExpr,
    resolve,
    undefinedRefs,
    issues,
    availableRefs,
  };
}

/**
 * 收集一个字段所有表达式属性的引用 key —— 复制 / 删除字段前做影响面分析。
 */
export function fieldReferences(field: FormFieldSchema): string[] {
  const keys = new Set<string>();
  for (const attr of EXPR_ATTRS) {
    for (const k of referencedFieldKeys(field[attr] ?? '')) keys.add(k);
  }
  return [...keys];
}