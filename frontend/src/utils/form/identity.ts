/**
 * 新建字段的「显示名 + 标识 key」预填规则。
 *
 * 为什么要预填：这两个值是表单的对外契约（key 进数据结构、也进 `{{$form.xxx}}`
 * 表达式），但新建时既要想中文名、又想英文 key，大部分人第一步都会卡住。
 * 系统先给一个合规的默认值，用户不满意再改 —— 比留空让人对着红框发呆强。
 *
 * 规则：
 *   标识 key = 类型前缀 + 序号（text1 / text2 / select1 …），序号从 1 起并跳过已占用的
 *   显示名   = 类型显示名 + 同一个序号（「单行文本 1」）
 * 两者都保证在当前表单内唯一。
 *
 * 这一层**不依赖 i18n / vue / pinia**：类型显示名由调用方传进来（store 里从
 * `formDesigner.type.*` 取），所以规则本身可以脱离组件单独测。
 */

import type { FieldType } from '@/api/formDesign';

/**
 * 字段类型 → 标识 key 前缀。
 *
 * 用 lowerCamelCase 而不是类型枚举名：key 会直接进数据结构和表达式，
 * `richText2` 比 `RICH_TEXT_2` 好读也好写。
 */
export const KEY_PREFIX: Record<FieldType, string> = {
  TEXT: 'text',
  TEXTAREA: 'textarea',
  NUMBER: 'number',
  SELECT: 'select',
  RADIO: 'radio',
  CHECKBOX: 'checkbox',
  DATE: 'date',
  DATETIME: 'datetime',
  USER: 'user',
  DEPT: 'dept',
  FILE: 'file',
  RICH_TEXT: 'richText',
  TABLE: 'table',
  DIVIDER: 'divider',
};

export interface DefaultIdentity {
  /** 标识 key */
  key: string;
  /** 显示名 */
  label: string;
}

/**
 * 按规则算出一组默认的「显示名 + 标识 key」。
 *
 * @param type      字段类型
 * @param takenKeys 当前表单已占用的 key（用于跳号，保证唯一）
 * @param typeLabel 该类型的显示名，由调用方从 i18n 取（如「单行文本」）
 */
export function defaultIdentity(
  type: FieldType,
  takenKeys: Iterable<string>,
  typeLabel: string,
): DefaultIdentity {
  const prefix = KEY_PREFIX[type] ?? 'field';

  const used = new Set<string>();
  for (const k of takenKeys) {
    const s = (k ?? '').trim();
    if (s) used.add(s);
  }

  let n = 1;
  while (used.has(`${prefix}${n}`)) n++;

  return { key: `${prefix}${n}`, label: `${typeLabel} ${n}` };
}